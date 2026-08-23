package com.st.patch.common

/**
 * Termux 侧脚本生成，三个互操作脚本的单一事实来源：
 * 数据迁移 export-data.sh、修补 apply.sh、接管 takeover.sh。
 *
 * 酒馆目录不能假定名称（~/SillyTavern、~/st 只是本机手动搭环境的结果），必须特征扫描识别
 * （server.js + 同目录 package.json 含 sillytavern）。原版与补丁版的特征文件完全相同，
 * 扫描无法区分，靠打包时写入的 .st-patch-build 版本标记（见 prepare-assets.sh）区分：
 * 无标记 = 原版；有标记 = 补丁版；标记版本 ≠ 当前补丁包 = 旧补丁（可升级覆盖）。
 * 标记格式 <酒馆版本>-patch-<补丁版本>（如 1.18.0-patch-2.2.1），修补时提取酒馆版本按方向判定：
 * 补丁底座 ≥ 目标=可覆盖（同版直接修、高底座覆盖低版=升修补，全量覆盖是酒馆官方更新方式）；
 * 补丁底座 < 目标=降级（一律拒绝——unzip -o 不删旧文件，降级会残留新版本文件造成混合态）。
 */
object TermuxScripts {

    private const val SHEBANG = "#!/data/data/com.termux/files/usr/bin/sh"
    private const val MARKER = ".st-patch-build"
    private const val APP_ZIP = "/sdcard/Download/st-patch/app.zip"

    /**
     * 修补结果回传文件（与 PatchExporter.PUBLIC_DIR/patch-result.txt 对应）：
     * app 经 MediaStore 预创建（归 app 所有 → app 免权限读回），apply.sh 结束时覆写为
     * status=ok|skipped|error + target/version/detail。RUN_COMMAND 无执行回传通道，
     * 这是唯一闭环；Termux 无存储权限时写不进来（app 侧超时降级为手动指引）。
     */
    const val APPLY_RESULT_PATH = "/sdcard/Download/st-patch/patch-result.txt"

    /**
     * 影子导出结果回传文件（同 patch-result 机制）：shadow-export.sh 结束时覆写
     * status=ok|error + target/detail，app 轮询自己的 uri 读回。
     */
    const val SHADOW_RESULT_PATH = "/sdcard/Download/st-patch/shadow-result.txt"

    /**
     * 影子打包进度回传文件：脚本打包期间后台循环覆写 percent=0..100
     * （du 总大小 vs tar 已写字节），app 同一预创建机制免权限轮询读回，驱动真实进度条。
     */
    const val SHADOW_PROGRESS_PATH = "/sdcard/Download/st-patch/shadow-progress.txt"

    /**
     * 调试日志重定向片段：脚本全部输出落到 Download 的 <logName>（app/adb 可读），
     * Termux 无 storage 写权限时静默跳过。后台 RUN_COMMAND 会话无可见输出，全靠它排障。
     */
    private fun dbgBlock(logName: String): String {
        val D = "${'$'}"
        return "DBG=/sdcard/Download/st-patch/$logName\n" +
            "if [ -d /sdcard/Download/st-patch ] && touch \"${D}DBG\" 2>/dev/null; then\n" +
            "  exec >> \"${D}DBG\" 2>&1\n" +
            "fi\n" +
            "echo \"[${D}(date '+%F %T')] 脚本启动，参数：${D}*，HOME=${D}HOME\"\n"
    }

    /**
     * 酒馆目录识别片段（sh）：置 ST 变量并校验，找不到则报错退出。
     * 扫 $HOME 三层深度（剪 node_modules/.git），多个候选取 data/ 内文件 mtime 最新者
     * （用户最近在用的那个）；ST_DIR 环境变量可手动指定。
     *
     * @param forPatch 修补模式 = true：只把「原版 / 旧版补丁」当目标，已是当前补丁版的候选跳过；
     *                 版本不一致按方向处理——目标更新=降级（一律跳过，覆盖残留新文件）；
     *                 目标更旧=可升修补（ALLOW_UPGRADE=1 才放行，否则跳过回传 direction=upgradeable）。
     * @param forShadow 影子模式 = true：只把「原版」（无 .st-patch-build 标记）当目标，
     *                 补丁版候选一律跳过——影子复制的就是原版酒馆，与 app 自带补丁版区分。
     *                 迁移/接管 = 两者均 false：所有候选参与，不区分（要的是数据/正在跑的实例）。
     * @param resultMode = true：失败出口（全部候选被跳过 / 未找到）在退出前设置 STATUS/DETAIL，
     *                 配合脚本的 trap write_result EXIT 把结果回传给 app。
     */
    fun stDetectBlock(
        forPatch: Boolean = false,
        resultMode: Boolean = false,
        forShadow: Boolean = false
    ): String {
        val D = "${'$'}"
        val curRead = if (forPatch)
            "  # 当前补丁包的版本标记（原版/补丁版特征相同，靠 $MARKER 区分）\n" +
            "  CUR=\"${D}(unzip -p $APP_ZIP $MARKER 2>/dev/null)\"\n" +
            "  # 提取补丁对应的酒馆版本（标记格式 <酒馆版本>-patch-<补丁版本>）\n" +
            "  case \"${D}CUR\" in *-patch-*) CUR_ST=\"${D}(printf '%s' \"${D}CUR\" | sed 's/-patch-.*//')\";; *) CUR_ST=\"\";; esac\n" +
            "  MISMATCH_UP=0  MISMATCH_DOWN=0  UP_CUR=\"\" UP_TARGET=\"\" DOWN_CUR=\"\" DOWN_TARGET=\"\"\n" +
            "  # 语义版本比较：$1 vs $2 → CMP_RESULT -1(v1<v2)/0(等)/1(v1>v2)；逐段按数字比，避免 1.9>1.10 字典序错误\n" +
            "  ver_cmp() {\n" +
            "    V1=\"${D}1\" V2=\"${D}2\" CMP_RESULT=0\n" +
            "    while [ -n \"${D}V1\" ] || [ -n \"${D}V2\" ]; do\n" +
            "      A=\"${D}{V1%%.*}\" B=\"${D}{V2%%.*}\"\n" +
            "      case \"${D}A\" in ''|*[!0-9]*) A=0;; esac\n" +
            "      case \"${D}B\" in ''|*[!0-9]*) B=0;; esac\n" +
            "      while [ \"${D}{#A}\" -gt 1 ] && [ \"${D}{A#0}\" != \"${D}A\" ]; do A=\"${D}{A#0}\"; done\n" +
            "      while [ \"${D}{#B}\" -gt 1 ] && [ \"${D}{B#0}\" != \"${D}B\" ]; do B=\"${D}{B#0}\"; done\n" +
            "      [ \"${D}A\" -lt \"${D}B\" ] && { CMP_RESULT=-1; return; }\n" +
            "      [ \"${D}A\" -gt \"${D}B\" ] && { CMP_RESULT=1; return; }\n" +
            "      case \"${D}V1\" in *.*) V1=\"${D}{V1#*.}\";; *) V1=\"\";; esac\n" +
            "      case \"${D}V2\" in *.*) V2=\"${D}{V2#*.}\";; *) V2=\"\";; esac\n" +
            "    done\n" +
            "  }\n"
        else ""
        val classify = when {
            forPatch ->
                "    M=\"${D}(cat \"${D}d/$MARKER\" 2>/dev/null)\"\n" +
                "    if [ -n \"${D}M\" ] && [ \"${D}M\" = \"${D}CUR\" ]; then\n" +
                "      echo \"跳过 ${D}d（已是当前补丁版 ${D}M）\"\n" +
                "      SKIPPED=${D}((SKIPPED+1))\n" +
                "      continue\n" +
                "    fi\n" +
                "    # 提取目标酒馆版本：有标记取标记前缀，无标记（原版）取 package.json 的 version\n" +
                "    if [ -n \"${D}M\" ]; then\n" +
                "      case \"${D}M\" in *-patch-*) TARGET_ST=\"${D}(printf '%s' \"${D}M\" | sed 's/-patch-.*//')\";; *) TARGET_ST=\"\";; esac\n" +
                "    else\n" +
                "      TARGET_ST=\"${D}(grep -m1 '\"version\"' \"${D}d/package.json\" 2>/dev/null | sed 's/.*\"version\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/')\"\n" +
                "    fi\n" +
                "    # 版本方向判定：全量覆盖只允许「升修补」（补丁底座 ≥ 目标）；降级覆盖会残留新文件不安全\n" +
                "    if [ -n \"${D}CUR_ST\" ] && [ -n \"${D}TARGET_ST\" ]; then\n" +
                "      ver_cmp \"${D}TARGET_ST\" \"${D}CUR_ST\"  # CMP_RESULT: -1=目标旧 0=同 1=目标新\n" +
                "      if [ \"${D}CMP_RESULT\" -eq 1 ]; then\n" +
                "        echo \"跳过 ${D}d（降级不安全：补丁${D}CUR_ST < 目标${D}TARGET_ST，覆盖会残留新文件）\"\n" +
                "        MISMATCH_DOWN=${D}((MISMATCH_DOWN+1))\n" +
                "        [ -z \"${D}DOWN_CUR\" ] && { DOWN_CUR=\"${D}CUR_ST\"; DOWN_TARGET=\"${D}TARGET_ST\"; }\n" +
                "        continue\n" +
                "      elif [ \"${D}CMP_RESULT\" -eq -1 ] && [ \"${D}ALLOW_UPGRADE\" != 1 ]; then\n" +
                "        echo \"跳过 ${D}d（可升修补：补丁${D}CUR_ST > 目标${D}TARGET_ST，待确认）\"\n" +
                "        MISMATCH_UP=${D}((MISMATCH_UP+1))\n" +
                "        [ -z \"${D}UP_CUR\" ] && { UP_CUR=\"${D}CUR_ST\"; UP_TARGET=\"${D}TARGET_ST\"; }\n" +
                "        continue\n" +
                "      fi\n" +
                "    fi\n" +
                "    if [ -n \"${D}M\" ]; then LABEL=\"补丁版(旧 ${D}M，将升级)\"; else LABEL=\"原版\"; fi\n"
            forShadow ->
                "    M=\"${D}(cat \"${D}d/$MARKER\" 2>/dev/null)\"\n" +
                "    if [ -n \"${D}M\" ]; then\n" +
                "      echo \"跳过 ${D}d（补丁版 ${D}M，影子只复制原版）\"\n" +
                "      SKIPPED=${D}((SKIPPED+1))\n" +
                "      continue\n" +
                "    fi\n" +
                "    LABEL=\"原版\"\n"
            else -> "    LABEL=\"酒馆\"\n"
        }
        // 候选全被跳过时的出口：修补=可升修补/降级阻断/无需操作（version_mismatch|skipped/exit 0）；影子=没复制到目标（error/exit 1）
        val skipBody = when {
            forShadow ->
                "    echo \"所有候选均为补丁版（有 $MARKER 标记），未找到原版酒馆可复制。\"\n" +
                "    STATUS=\"error\"\n" +
                "    DETAIL=\"所有候选均为补丁版，未找到原版酒馆\"\n" +
                "    exit 1\n"
            resultMode ->
                "    if [ \"${D}{MISMATCH_UP:-0}\" -gt 0 ]; then\n" +
                "      echo \"可升修补：补丁${D}{UP_CUR} > 目标酒馆${D}{UP_TARGET}（高底座全量覆盖低版本，合法）\"\n" +
                "      STATUS=\"version_mismatch\"\n" +
                "      DIRECTION=\"upgradeable\"\n" +
                "      DETAIL=\"补丁${D}{UP_CUR} → 目标酒馆${D}{UP_TARGET}\"\n" +
                "      exit 0\n" +
                "    fi\n" +
                "    if [ \"${D}{MISMATCH_DOWN:-0}\" -gt 0 ]; then\n" +
                "      echo \"不可修补：补丁${D}{DOWN_CUR} < 目标酒馆${D}{DOWN_TARGET}（降级覆盖残留新文件）\"\n" +
                "      STATUS=\"version_mismatch\"\n" +
                "      DIRECTION=\"blocked\"\n" +
                "      DETAIL=\"补丁${D}{DOWN_CUR} < 目标酒馆${D}{DOWN_TARGET}（降级不安全，请换更高补丁包）\"\n" +
                "      exit 0\n" +
                "    fi\n" +
                "    echo \"所有候选均已是当前补丁版${D}{CUR:+（${D}CUR）}，无需修补。\"\n" +
                "    STATUS=\"skipped\"\n" +
                "    DETAIL=\"所有候选均已是当前补丁版${D}{CUR:+（${D}CUR）}\"\n" +
                "    exit 0\n"
            else -> "    exit 0\n"
        }
        val notFoundStatus = if (resultMode)
            "  STATUS=\"error\"\n" +
            "  DETAIL=\"未找到酒馆特征目录（server.js + package.json 含 sillytavern）\"\n"
        else ""
        return """
# ---- 酒馆目录识别：扫描特征文件（server.js + 同目录 package.json 含 sillytavern），目录名不限 ----
# 多个候选时取 data/ 内文件 mtime 最新的（最近在用的那个）；也可用环境变量 ST_DIR=/路径 手动指定。
ST=""
if [ -n "${D}ST_DIR" ] && [ -f "${D}ST_DIR/server.js" ]; then
  ST="${D}ST_DIR"
  echo "ST_DIR 手动指定：${D}ST"
else
  CAND="${D}(mktemp 2>/dev/null || echo /data/data/com.termux/files/usr/tmp/st-candidates.${D}${D})"
  find "${D}HOME" -maxdepth 3 \( -name node_modules -o -name .git \) -prune -o -type f -name server.js -print 2>/dev/null | while read -r f; do
    d="${D}{f%/*}"
    if [ -f "${D}d/package.json" ] && grep -qi sillytavern "${D}d/package.json" 2>/dev/null; then
      echo "${D}d"
    fi
  done > "${D}CAND"
  SKIPPED=0
${curRead}  BEST_T=0
  while read -r d; do
    [ -n "${D}d" ] || continue
${classify}    T=0
    if [ -d "${D}d/data" ]; then
      T="${D}(find "${D}d/data" -type f -printf '%T@\n' 2>/dev/null | sort -rn | head -n 1 | cut -d. -f 1)"
    fi
    case "${D}T" in ''|*[!0-9]*) T=0;; esac
    if [ "${D}T" -gt 0 ]; then
      W="${D}(date -d @"${D}T" '+%F %T' 2>/dev/null || echo "${D}T")"
    else
      W="无 data"
    fi
    echo "候选：${D}d [${D}LABEL]（data 最近活动：${D}W）"
    if [ "${D}T" -gt "${D}BEST_T" ]; then BEST="${D}d"; BEST_T="${D}T"; fi
  done < "${D}CAND"
  rm -f "${D}CAND" 2>/dev/null
  ST="${D}BEST"
fi
if [ -z "${D}ST" ] || [ ! -f "${D}ST/server.js" ]; then
  if [ "${D}{SKIPPED:-0}" -gt 0 ] || [ "${D}{MISMATCH_UP:-0}" -gt 0 ] || [ "${D}{MISMATCH_DOWN:-0}" -gt 0 ]; then
${skipBody}  fi
  echo '未在 ${D}HOME 三层深度内找到酒馆特征目录（server.js + package.json 含 sillytavern）。'
  echo '若目录更深或识别有误，可手动指定后重跑：ST_DIR=/酒馆/路径 sh 本脚本'
${notFoundStatus}  exit 1
fi
echo "目标酒馆：${D}ST"
"""
    }

    /**
     * 数据迁移导出脚本：识别酒馆目录后，tar 打包所选分类到 Download/st-patch/data.tar。
     * 用 tar 不用 zip——tar 是 Termux 引导包基础组件，用户无需 pkg install zip。
     * 只读打包（复制语义），不删除、不修改 Termux 内任何原数据。
     * @param categories data/default-user/ 下的相对路径（characters/chats/secrets.json/settings.json/extensions）
     */
    fun exportDataScript(categories: List<String>): String {
        require(categories.isNotEmpty()) { "categories must not be empty" }
        val D = "${'$'}"
        val tarArgs = categories.joinToString(" ") { "\"data/default-user/$it\"" }
        return SHEBANG + "\n" +
            "# 由 SillyTavern Patch 生成：把所选酒馆数据打包成 tar 供 app 导入（只读打包，不动原数据）。\n" +
            dbgBlock("export-debug.log") +
            "if ! command -v tar >/dev/null 2>&1; then\n" +
            "  echo \"未找到 tar（Termux 基础包应自带，请勿卸载）\"\n" +
            "  exit 2\n" +
            "fi\n" +
            stDetectBlock() +
            """
[ -d "${D}ST/data" ] || { echo "该酒馆无 data 目录（无用户数据可导出）"; exit 1; }
# 仅打包实际存在的所选分类（未勾选/不存在的自动跳过）
ARGS=""
for p in $tarArgs; do
  [ -e "${D}ST/${D}p" ] && ARGS="${D}ARGS ${D}p"
done
[ -n "${D}ARGS" ] || { echo "所选分类在该酒馆中均不存在"; exit 1; }
OUT=/sdcard/Download/st-patch/data.tar
rm -f "${D}OUT" 2>/dev/null
(cd "${D}ST" && tar -cf "${D}OUT" ${D}ARGS) || { echo "tar 打包失败"; exit 3; }
echo "导出完成：${D}OUT（Termux 原数据未做任何改动）"
echo "回到 SillyTavern Patch 点「导入数据」选择该文件。"
"""
    }

    /**
     * 修补脚本：识别目标（原版或旧版补丁，已是当前补丁版的跳过）后停 node，
     * unzip 覆盖代码（app.zip 无 data 条目，用户数据不动），并写入补丁标记。
     * 版本不一致按方向判定：降级（补丁底座 < 目标）一律拒绝；
     * 升修补（补丁底座 > 目标）须 [allowUpgrade]=true 才放行，否则回传 direction=upgradeable 由 app 确认。
     * 结束时经 trap EXIT 把结果（ok/skipped/version_mismatch/error + target/version/direction/detail）覆写到 [APPLY_RESULT_PATH]
     * （app 预创建、归 app 所有，故 app 能免权限读回实现结果反馈）。
     * @param allowUpgrade true=允许高底座补丁覆盖低版本酒馆（升修补）；false=同版才修，否则回传方向待确认
     */
    fun applyScript(allowUpgrade: Boolean = false): String {
        val D = "${'$'}"
        return SHEBANG + "\n" +
            "# 由 SillyTavern Patch 生成：用补丁版酒馆覆盖 Termux 原版酒馆代码（保留 data/ 与 config.yaml）。\n" +
            dbgBlock("apply-debug.log") +
            "# 结果回传：每个出口经 trap 写入 app 预创建的结果文件（写不进则静默，app 侧超时降级）\n" +
            "RESULT=$APPLY_RESULT_PATH\n" +
            "ALLOW_UPGRADE=${if (allowUpgrade) "1" else "0"}\n" +
            "STATUS=\"error\"\n" +
            "DETAIL=\"脚本异常中断（未见任何出口）\"\n" +
            "write_result() {\n" +
            "  printf 'status=%s\\ntarget=%s\\nversion=%s\\ndirection=%s\\ndetail=%s\\n' \"${D}STATUS\" \"${D}{ST:-}\" \"${D}{CUR:-}\" \"${D}{DIRECTION:-}\" \"${D}DETAIL\" > \"${D}RESULT\" 2>/dev/null || true\n" +
            "}\n" +
            "trap write_result EXIT\n" +
            "# 先确认 unzip 可用（识别当前补丁版本标记也依赖它）\n" +
            "UNZIP_BIN=\"\"\n" +
            "if command -v unzip >/dev/null 2>&1; then UNZIP_BIN=unzip\n" +
            "elif [ -x /system/bin/unzip ]; then UNZIP_BIN=/system/bin/unzip\n" +
            "else\n" +
            "  echo \"未找到 unzip，请先执行：pkg install unzip\"\n" +
            "  STATUS=\"error\"; DETAIL=\"未找到 unzip，请先在 Termux 执行：pkg install unzip\"\n" +
            "  exit 2\n" +
            "fi\n" +
            stDetectBlock(forPatch = true, resultMode = true) +
            """
pkill -f 'node.*server.js' 2>/dev/null || true
sleep 1
ZIP=$APP_ZIP
# app.zip 无 data/ 条目，覆盖即升级代码，用户数据不动
if ! "${D}UNZIP_BIN" -o "${D}ZIP" -d "${D}ST" >/dev/null; then
  STATUS="error"; DETAIL="unzip 覆盖失败（app.zip 损坏或存储空间不足？）"
  echo "unzip 覆盖失败"
  exit 4
fi
printf '%s\n' "${D}{CUR:-unknown}" > "${D}ST/$MARKER"
STATUS="ok"
DETAIL="已覆盖为补丁版 ${D}{CUR:-unknown}，数据未动"
echo "修补完成：${D}ST"
echo "可回到 SillyTavern Patch 用「接管」模式运行。"
"""
    }

    /**
     * 影子导出脚本：识别「原版」酒馆目录（无 .st-patch-build 标记；补丁版候选一律跳过——
     * 影子复制的就是原版，与 app 自带补丁版区分；全是补丁版时回传 error）后 tar 整目录
     * 到 Download/st-patch/shadow.tar，供 app 导入为影子副本。原版目录常无 node_modules
     * （在 Termux 从未运行过），app 导入后自动从内置包补齐依赖。
     * 只读打包（复制语义），不删除、不修改 Termux 内任何原数据。
     * 进度回传：du 算源目录总大小，后台循环按 tar 已写字节算百分比覆写 [SHADOW_PROGRESS_PATH]
     * （后台 RUN_COMMAND 会话无可见输出，app 侧靠它驱动真实进度条）。
     * 结束时经 trap EXIT 把结果（ok/error + 原因）覆写到 [SHADOW_RESULT_PATH]。
     * tar -C 进入酒馆根打包 .，条目以 ./ 开头，app 侧导入统一去该前缀。
     */
    fun shadowScript(): String {
        val D = "${'$'}"
        return SHEBANG + "\n" +
            "# 由 SillyTavern Patch 生成：把原版酒馆整目录打包成 tar 供 app 影子导入（只读打包，不动原数据）。\n" +
            dbgBlock("shadow-debug.log") +
            "# 结果/进度回传：均写入 app 预创建的文件（写不进则静默，app 侧超时降级）\n" +
            "RESULT=$SHADOW_RESULT_PATH\n" +
            "PROGRESS=$SHADOW_PROGRESS_PATH\n" +
            "STATUS=\"error\"\n" +
            "DETAIL=\"脚本异常中断（未见任何出口）\"\n" +
            "write_result() {\n" +
            "  printf 'status=%s\\ntarget=%s\\ndetail=%s\\n' \"${D}STATUS\" \"${D}{ST:-}\" \"${D}DETAIL\" > \"${D}RESULT\" 2>/dev/null || true\n" +
            "}\n" +
            "trap write_result EXIT\n" +
            "if ! command -v tar >/dev/null 2>&1; then\n" +
            "  echo \"未找到 tar（Termux 基础包应自带，请勿卸载）\"\n" +
            "  STATUS=\"error\"; DETAIL=\"未找到 tar\"\n" +
            "  exit 2\n" +
            "fi\n" +
            stDetectBlock(resultMode = true, forShadow = true) +
            """
OUT=/sdcard/Download/st-patch/shadow.tar
rm -f "${D}OUT" 2>/dev/null
# 前台会话可见：进度同时写 /dev/tty（后台会话无 tty，静默跳过不影响）
printf '开始打包原版酒馆…\n' > /dev/tty 2>/dev/null || true
# 打包进度：du 算源目录总大小（KB），后台循环按 tar 已写字节算百分比覆写进度文件
TOTAL_KB="${D}(du -sk "${D}ST" 2>/dev/null | cut -f1)"
case "${D}TOTAL_KB" in ''|*[!0-9]*) TOTAL_KB=0;; esac
echo "源目录总大小：${D}{TOTAL_KB}KB"
PROGRESS_PID=""
if [ "${D}TOTAL_KB" -gt 0 ]; then
  (
    while :; do
      CUR_KB=${D}(( ${D}(stat -c %s "${D}OUT" 2>/dev/null || echo 0) / 1024 ))
      P=${D}(( CUR_KB * 100 / TOTAL_KB ))
      [ "${D}P" -gt 100 ] && P=100
      printf 'percent=%s\n' "${D}P" > "${D}PROGRESS" 2>/dev/null || true
      printf '打包中 %s%%（已写 %s KB / 约 %s KB）\r' "${D}P" "${D}CUR_KB" "${D}TOTAL_KB" > /dev/tty 2>/dev/null || true
      sleep 2
    done
  ) &
  PROGRESS_PID=${D}!
fi
# -C 进入酒馆根再打包 .（条目以 ./ 开头，app 侧导入时去前缀）；整目录含 node_modules 与 data
if ! (cd "${D}ST" && tar -cf "${D}OUT" .); then
  [ -n "${D}PROGRESS_PID" ] && kill "${D}PROGRESS_PID" 2>/dev/null
  printf '\ntar 打包失败，详情见 Download/st-patch/shadow-debug.log\n' > /dev/tty 2>/dev/null || true
  STATUS="error"; DETAIL="tar 打包失败（存储空间不足？整目录可能很大）"
  echo "tar 打包失败"
  exit 3
fi
[ -n "${D}PROGRESS_PID" ] && kill "${D}PROGRESS_PID" 2>/dev/null
STATUS="ok"
DETAIL="已打包：${D}OUT"
echo "影子导出完成：${D}ST → ${D}OUT（Termux 原数据未做任何改动）"
printf '\n影子导出完成，请回到 SillyTavern Patch 点「导入影子副本」。\n' > /dev/tty 2>/dev/null || true
"""
    }

    /**
     * 接管启动脚本：识别酒馆目录（不区分原版/补丁版，取最近在用的）后带守卫启动——
     * 后台跑 node，循环探测 app 的 beacon 端口，连续 3 次失败即 kill node
     * （app 退出/被杀 → beacon 关闭 → node 跟随退出）。
     * 参数1 = beacon 端口；启动/停止事件与 node 输出写 ~/st-takeover.log。
     */
    fun takeoverScript(beaconPort: Int): String {
        val D = "${'$'}"
        val probe = "var n=require(\"net\").connect($beaconPort,\"127.0.0.1\");" +
            "n.on(\"connect\",function(){process.exit(0)});" +
            "n.on(\"error\",function(){process.exit(1)});" +
            "setTimeout(function(){process.exit(1)},1000)"
        return SHEBANG + "\n" +
            "# 由 SillyTavern Patch 生成：识别酒馆并带守卫启动（参数1=beacon 端口；app 消失即停）。\n" +
            "BEACON=\"${D}1\"\n" +
            "[ -n \"${D}BEACON\" ] || { echo '缺少 beacon 端口参数'; exit 1; }\n" +
            "LOG=\"${D}HOME/st-takeover.log\"\n" +
            dbgBlock("takeover-debug.log") +
            "for t in find grep sort head cut date mktemp cat; do command -v \"${D}t\" >/dev/null 2>&1 || echo \"缺少工具：${D}t\"; done\n" +
            stDetectBlock() +
            """
cd "${D}ST" || { echo "无法进入 ${D}ST"; exit 1; }
echo "[${D}(date '+%F %T')] 接管启动：${D}ST" >> "${D}LOG"
HOME="${D}HOME" LD_LIBRARY_PATH="${TermuxBridge.TERMUX_LIB}" NODE_ENV=production SILLYTAVERN_BROWSERLAUNCH_ENABLED=false \
  ${TermuxBridge.TERMUX_NODE} server.js --dataRoot "${D}ST/data" >> "${D}LOG" 2>&1 &
NPID=${D}!
MISSES=0
while kill -0 "${D}NPID" 2>/dev/null; do
  if ${TermuxBridge.TERMUX_NODE} -e '$probe' >/dev/null 2>&1; then
    MISSES=0
  else
    MISSES=${D}((MISSES+1))
    if [ "${D}MISSES" -ge 3 ]; then
      kill "${D}NPID" 2>/dev/null
      echo "[${D}(date '+%F %T')] app 已消失，停止 node" >> "${D}LOG"
      break
    fi
  fi
  sleep 3
done
wait "${D}NPID" 2>/dev/null
true
"""
    }
}
