package com.st.patch.common;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 从 APK assets 解压资源到私有目录。
 * 使用迭代方式避免递归栈溢出（assets 可能包含 14000+ 文件）。
 */
public class ExtractAssets {
    private static final HashMap<String, String> extractHistory = new HashMap<>();
    private static final int BUFFER_SIZE = 65536; // 64KB buffer

    private final Context context;

    public ExtractAssets(Context context) {
        this.context = context;
    }

    /**
     * 递归解压 assets 目录到 filesDir，使用迭代避免栈溢出。
     */
    public String extractResources(String dir) {
        if (dir == null || dir.isEmpty()) return null;

        if (extractHistory.containsKey(dir)) {
            return extractHistory.get(dir);
        }

        if (dir.startsWith("file:///android_asset/")) {
            dir = dir.substring("file:///android_asset/".length());
        }
        if (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }

        String outputDir = getExtractPath(dir);
        try {
            // 使用队列迭代遍历，避免递归栈溢出
            Deque<String> queue = new ArrayDeque<>();
            queue.add(dir);

            while (!queue.isEmpty()) {
                String current = queue.poll();
                String[] files = context.getAssets().list(current);

                if (files == null || files.length == 0) {
                    // 是文件，解压它
                    extractSingleFile(current);
                } else {
                    // 是目录，确保目标目录存在，子项入队
                    new File(getExtractPath(current)).mkdirs();
                    for (String file : files) {
                        queue.add(current + "/" + file);
                    }
                }
            }

            extractHistory.put(dir, outputDir);
            return outputDir;
        } catch (Exception e) {
            // 如果 list 失败，尝试作为单文件处理
            try {
                extractSingleFile(dir);
                extractHistory.put(dir, outputDir);
                return outputDir;
            } catch (Exception ignored) {
            }
        }

        return "";
    }

    private void extractSingleFile(String relativePath) {
        String outPath = getExtractPath(relativePath);
        File outFile = new File(outPath);
        File parentDir = outFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (InputStream in = context.getAssets().open(relativePath);
             FileOutputStream out = new FileOutputStream(outFile)) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            out.flush();

        } catch (IOException e) {
            // 静默跳过无法读取的文件
        }

        // 设置权限
        outFile.setReadable(true, false);
        outFile.setWritable(true, true);
        if (outFile.getName().equals("node") || outFile.getName().endsWith(".sh")) {
            outFile.setExecutable(true, false);
        }
    }

    /**
     * 从 APK assets 中的单个 zip 归档流式解压到 filesDir。
     *
     * 相比 extractResources 对每个文件单独 AssetManager.open，
     * 这里只打开一次输入流顺序读取，把 14000+ 次条目定位降为 1 次。
     *
     * 原子落地：先解压到 .app_tmp/，全部成功后再替换正式目录，避免半解压状态被当作已就绪。
     *
     * @param assetPath assets 中的 zip 路径，如 "sillytavern/app.zip"
     * @param destDir   解压目标根目录（条目会保存在其下，如 .../sillytavern/app/...）
     * @return 目标根目录路径
     * @throws IOException 解压任一条目失败时抛出，调用方应广播失败而非继续启动 Node
     */
    public String extractZipArchive(String assetPath, File destDir) throws IOException {
        String destKey = destDir.getCanonicalPath();
        if (extractHistory.containsKey(destKey)) {
            return extractHistory.get(destKey);
        }

        if (!destDir.exists()) {
            destDir.mkdirs();
        }

        // 原子落地用的临时目录
        File tmpDir = new File(destDir, ".app_tmp");
        deleteRecursively(tmpDir);
        tmpDir.mkdirs();

        // 标记正在解压（调用方据此在中断后强制重解）
        File extracting = new File(destDir, ".extracting");
        extracting.createNewFile();

        try (InputStream raw = context.getAssets().open(assetPath);
             ZipInputStream zis = new ZipInputStream(new BufferedInputStream(raw, BUFFER_SIZE))) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }

                // 防路径穿越：拒绝绝对路径或包含 .. 的条目
                if (name.startsWith("/") || name.contains("..")) {
                    zis.closeEntry();
                    continue;
                }
                // 强制正斜杠统一处理
                name = name.replace('\\', '/');

                File outFile = new File(tmpDir, name);
                String canonical = outFile.getCanonicalPath();
                String tmpBase = tmpDir.getCanonicalPath();
                if (!canonical.startsWith(tmpBase + File.separator)) {
                    // 不在临时根下，拒绝
                    zis.closeEntry();
                    continue;
                }

                if (entry.isDirectory()) {
                    outFile.mkdirs();
                    continue;
                }

                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }

                // 写入文件
                try (FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                    out.flush();
                }

                // 权限语义与 extractSingleFile 一致
                outFile.setReadable(true, false);
                outFile.setWritable(true, true);
                String fname = outFile.getName();
                if (fname.equals("node") || fname.endsWith(".sh")) {
                    outFile.setExecutable(true, false);
                }

                zis.closeEntry();
            }

            // 全部成功：删除旧正式目录（如非首次/升级场景），把临时目录就地 rename
            File finalAppDir = new File(destDir, "app");
            if (finalAppDir.exists()) {
                deleteRecursively(finalAppDir);
            }
            // rename 为 app
            boolean renamed = tmpDir.renameTo(finalAppDir);
            if (!renamed) {
                // rename 跨某些挂载点可能失败，回退到拷贝
                copyDir(tmpDir, finalAppDir);
                deleteRecursively(tmpDir);
            }

            // 解压完成标记
            extracting.delete();

            extractHistory.put(destKey, finalAppDir.getAbsolutePath());
            return finalAppDir.getAbsolutePath();

        } catch (IOException e) {
            // 失败：清理临时目录与标记，向上抛出
            deleteRecursively(tmpDir);
            extracting.delete();
            throw e;
        }
    }

    private void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursively(c);
                }
            }
        }
        f.delete();
    }

    private void copyDir(File src, File dest) throws IOException {
        if (src.isDirectory()) {
            if (!dest.exists()) dest.mkdirs();
            File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) {
                    copyDir(c, new File(dest, c.getName()));
                }
            }
        } else {
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                while ((len = in.read(buffer)) > 0) {
                    out.write(buffer, 0, len);
                }
            }
        }
    }

    public String getExtractPath(String file) {
        String path = file;
        if (path.startsWith("file:///android_asset/")) {
            path = path.substring("file:///android_asset/".length());
        }
        return context.getFilesDir().getAbsolutePath() + "/" + path;
    }
}
