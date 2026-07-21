package com.st.patch.common;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;

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

    public String getExtractPath(String file) {
        String path = file;
        if (path.startsWith("file:///android_asset/")) {
            path = path.substring("file:///android_asset/".length());
        }
        return context.getFilesDir().getAbsolutePath() + "/" + path;
    }
}
