#!/system/bin/sh
# 统一维护 no-proot Termux host runtime 的 PATH、Git helper 和证书环境；
# 服务启动、设置页终端、扩展安装必须 source 这一份，避免各入口环境分叉。

ANDROID_SYSTEM_PATH="${ANDROID_SYSTEM_PATH:-/system/bin:/system/xbin}"
PATH="$ANDROID_SYSTEM_PATH"
export PATH

assert_file() {
	if [ ! -f "$1" ]; then
		echo "$2" >&2
		exit 1
	fi
}

assert_dir() {
	if [ ! -d "$1" ]; then
		echo "$2" >&2
		exit 1
	fi
}

link_target_matches() {
	[ -L "$1" ] && [ "$(readlink "$1" 2>/dev/null || true)" = "$2" ]
}

ensure_symlink() {
	link_target="$1"
	link_path="$2"
	if link_target_matches "$link_path" "$link_target"; then
		return 0
	fi

	rm -f "$link_path"
	ln -s "$link_target" "$link_path"
}

install_termux_command_links() {
	link_dir="$1"
	mkdir -p "$link_dir"
	rm -f \
		"$link_dir/node" \
		"$link_dir/git" \
		"$link_dir/curl" \
		"$link_dir/sh" \
		"$link_dir/bash" \
		"$link_dir/npm" \
		"$link_dir/npx" \
		"$link_dir/remote-http" \
		"$link_dir/remote-https" \
		"$link_dir/remote-ftp" \
		"$link_dir/remote-ftps"
	ensure_symlink "$TERMUX_NODE_BIN" "$link_dir/node"
	ensure_symlink "$TERMUX_GIT_BIN" "$link_dir/git"
	ensure_symlink "$TERMUX_CURL_BIN" "$link_dir/curl"
	ensure_symlink "$TERMUX_SH_BIN" "$link_dir/sh"
	# Git 的错误和部分执行路径使用裸 remote-https 名称；这里仍指向同一个 APK native helper。
	ensure_symlink "$TERMUX_GIT_REMOTE_HTTP_BIN" "$link_dir/remote-http"
	ensure_symlink "$TERMUX_GIT_REMOTE_HTTP_BIN" "$link_dir/remote-https"
	ensure_symlink "$TERMUX_GIT_REMOTE_HTTP_BIN" "$link_dir/remote-ftp"
	ensure_symlink "$TERMUX_GIT_REMOTE_HTTP_BIN" "$link_dir/remote-ftps"
	if [ -n "${TERMUX_BASH_BIN:-}" ] && [ -f "$TERMUX_BASH_BIN" ]; then
		ensure_symlink "$TERMUX_BASH_BIN" "$link_dir/bash"
	fi
}

install_termux_server_fast_command_links() {
	link_dir="$1"
	mkdir -p "$link_dir"
	rm -f \
		"$link_dir/node" \
		"$link_dir/git" \
		"$link_dir/curl" \
		"$link_dir/sh" \
		"$link_dir/bash" \
		"$link_dir/npm" \
		"$link_dir/npx" \
		"$link_dir/remote-http" \
		"$link_dir/remote-https" \
		"$link_dir/remote-ftp" \
		"$link_dir/remote-ftps"
	ensure_symlink "$TERMUX_NODE_BIN" "$link_dir/node"
	ensure_symlink "$TERMUX_CURL_BIN" "$link_dir/curl"
	ensure_symlink "$TERMUX_SH_BIN" "$link_dir/sh"
	if [ -n "${TERMUX_BASH_BIN:-}" ] && [ -f "$TERMUX_BASH_BIN" ]; then
		ensure_symlink "$TERMUX_BASH_BIN" "$link_dir/bash"
	fi
}

git_core_links_signature() {
	printf 'layout=2|git=%s|remote-http=%s|git-core=%s' \
		"$TERMUX_GIT_BIN" \
		"$TERMUX_GIT_REMOTE_HTTP_BIN" \
		"$HOST_PREFIX_DIR/libexec/git-core"
}

git_core_links_ready() {
	link_dir="$1"
	signature="$2"
	signature_file="$link_dir/.sillydroid-git-core-links.signature"
	[ -f "$signature_file" ] || return 1
	[ "$(cat "$signature_file" 2>/dev/null || true)" = "$signature" ] || return 1
	link_target_matches "$link_dir/git" "$TERMUX_GIT_BIN" || return 1
	link_target_matches "$link_dir/git-remote-http" "$TERMUX_GIT_REMOTE_HTTP_BIN" || return 1
	link_target_matches "$link_dir/git-remote-https" "$TERMUX_GIT_REMOTE_HTTP_BIN" || return 1
	link_target_matches "$link_dir/remote-https" "$TERMUX_GIT_REMOTE_HTTP_BIN" || return 1
	return 0
}

install_git_core_links() {
	link_dir="$1"
	git_core_dir="$HOST_PREFIX_DIR/libexec/git-core"
	signature="$(git_core_links_signature)"
	signature_file="$link_dir/.sillydroid-git-core-links.signature"
	mkdir -p "$link_dir"

	# Git helper 链接只和 APK nativeLibraryDir 及 host prefix 有关；
	# 热启动重复重建上百个链接会明显拖慢小米等设备的服务就绪时间。
	if git_core_links_ready "$link_dir" "$signature"; then
		return 0
	fi

	rm -f \
		"$link_dir"/git \
		"$link_dir"/git-* \
		"$link_dir"/remote-*
	ensure_symlink "$TERMUX_GIT_BIN" "$link_dir/git"
	ensure_symlink "$TERMUX_GIT_REMOTE_HTTP_BIN" "$link_dir/git-remote-http"
	ensure_symlink "$TERMUX_GIT_REMOTE_HTTP_BIN" "$link_dir/git-remote-https"
	ensure_symlink "$TERMUX_GIT_REMOTE_HTTP_BIN" "$link_dir/git-remote-ftp"
	ensure_symlink "$TERMUX_GIT_REMOTE_HTTP_BIN" "$link_dir/git-remote-ftps"
	ensure_symlink "$TERMUX_GIT_REMOTE_HTTP_BIN" "$link_dir/remote-http"
	ensure_symlink "$TERMUX_GIT_REMOTE_HTTP_BIN" "$link_dir/remote-https"
	ensure_symlink "$TERMUX_GIT_REMOTE_HTTP_BIN" "$link_dir/remote-ftp"
	ensure_symlink "$TERMUX_GIT_REMOTE_HTTP_BIN" "$link_dir/remote-ftps"
	for git_core_entry in "$git_core_dir"/git-*; do
		[ -L "$git_core_entry" ] || continue
		link_name="${git_core_entry##*/}"
		link_target="$(readlink "$git_core_entry" 2>/dev/null || true)"
		case "$link_target" in
			git|*/git|../libexec/git-core/git)
				ensure_symlink "$TERMUX_GIT_BIN" "$link_dir/$link_name"
				;;
			git-remote-http|*/git-remote-http)
				ensure_symlink "$TERMUX_GIT_REMOTE_HTTP_BIN" "$link_dir/$link_name"
				;;
		esac
	done
	printf '%s' "$signature" > "$signature_file"
}

prepare_termux_host_runtime() {
	assert_dir "$HOST_PREFIX_DIR" "缺少 host prefix 目录：$HOST_PREFIX_DIR"
	assert_dir "$HOST_NATIVE_LIB_DIR" "缺少 host native lib 目录：$HOST_NATIVE_LIB_DIR"
	assert_file "$TERMUX_NODE_BIN" "缺少 Termux Node 入口：$TERMUX_NODE_BIN"
	assert_file "$TERMUX_GIT_BIN" "缺少 Termux Git 入口：$TERMUX_GIT_BIN"
	assert_file "$TERMUX_GIT_REMOTE_HTTP_BIN" "缺少 Termux Git HTTPS helper 入口：$TERMUX_GIT_REMOTE_HTTP_BIN"
	assert_file "$TERMUX_CURL_BIN" "缺少 Termux curl 入口：$TERMUX_CURL_BIN"
	assert_file "$TERMUX_SH_BIN" "缺少 Termux shell 入口：$TERMUX_SH_BIN"

	mkdir -p "$HOST_TMP_DIR"
	chmod 1777 "$HOST_TMP_DIR"

	export LD_LIBRARY_PATH="$HOST_NATIVE_LIB_DIR:$HOST_PREFIX_DIR/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
	export PREFIX="$HOST_PREFIX_DIR"
	export TMPDIR="$HOST_TMP_DIR"
	export TMP="$HOST_TMP_DIR"
	export TEMP="$HOST_TMP_DIR"
	# npm lifecycle 默认 shell 来自 Termux 编译期 prefix，独立包名下会回到
	# /data/data/com.termux/files/usr/bin/sh 并触发 EACCES；强制改用 APK native shell。
	export SHELL="$TERMUX_SH_BIN"
	export npm_config_script_shell="$TERMUX_SH_BIN"
	export NPM_CONFIG_SCRIPT_SHELL="$TERMUX_SH_BIN"
	export GIT_EXEC_PATH="${GIT_EXEC_PATH:-$HOST_TMP_DIR/git-core}"
	export GIT_TEMPLATE_DIR="${GIT_TEMPLATE_DIR:-$HOST_PREFIX_DIR/share/git-core/templates}"
	# Termux Git 编译期系统配置/属性路径指向 com.termux；Android App 无权限读取时会让 clone/fetch 报权限警告或失败。
	export GIT_CONFIG_NOSYSTEM=1
	export GIT_ATTR_NOSYSTEM=1
	if [ -f "$HOST_PREFIX_DIR/etc/tls/cert.pem" ]; then
		export SSL_CERT_FILE="$HOST_PREFIX_DIR/etc/tls/cert.pem"
		export NODE_EXTRA_CA_CERTS="$HOST_PREFIX_DIR/etc/tls/cert.pem"
		export GIT_SSL_CAINFO="$HOST_PREFIX_DIR/etc/tls/cert.pem"
	fi
	if [ -f "$HOST_PREFIX_DIR/etc/tls/openssl.cnf" ]; then
		export OPENSSL_CONF="$HOST_PREFIX_DIR/etc/tls/openssl.cnf"
	fi
	npm_lifecycle_android_shim="$(cd "$BOOTSTRAP_ROOT/scripts" 2>/dev/null && pwd -P)/npm-lifecycle-android-shim.cjs"
	if [ -f "$npm_lifecycle_android_shim" ]; then
		case " ${NODE_OPTIONS:-} " in
			*" --require $npm_lifecycle_android_shim "*)
				;;
			*)
				# npm 生命周期脚本会调用 node_modules/.bin 下的 JS shim；Android 不允许直接 exec app 私有可写路径。
				# 预加载只在 npm CLI 进程内生效，负责把这类 JS bin 转回 native Node，并让 node-gyp 快速失败。
				export NODE_OPTIONS="--require $npm_lifecycle_android_shim ${NODE_OPTIONS:-}"
				;;
		esac
	fi

	# Android 禁止从 app 私有可写目录直接 exec ELF；PATH 只暴露指向 APK nativeLibraryDir 的 symlink。
	# Tavern 服务快速启动模式使用无 Git 命令目录，让上游启动期 Git 自动更新快速判定不可用；
	# npm/simple-git 的 partial env 修复仍保留，完整模式或设置页终端仍可暴露完整 Git 环境。
	case "${SILLYDROID_HOST_COMMAND_PROFILE:-full}" in
		server-fast|no-git|fast)
			install_termux_server_fast_command_links "$HOST_TMP_DIR/server-fast-bin"
			export SILLYDROID_HOST_COMMAND_PATH="$HOST_TMP_DIR/server-fast-bin"
			;;
		*)
			install_termux_command_links "$HOST_TMP_DIR/bin"
			# git fetch/clone 会二次 exec remote helper，helper 同样必须从 APK nativeLibraryDir 进入。
			install_git_core_links "$GIT_EXEC_PATH"
			export SILLYDROID_HOST_COMMAND_PATH="$HOST_TMP_DIR/bin"
			;;
	esac
	export PATH="$SILLYDROID_HOST_COMMAND_PATH:$ANDROID_SYSTEM_PATH"
}
