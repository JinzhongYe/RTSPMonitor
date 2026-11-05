#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
rtsp_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
rtsp_ndk="$rtsp_sdk/ndk/27.0.12077973"
rtsp_host=linux-x86_64
if [ "$(uname -s)" = Darwin ]; then rtsp_host=darwin-x86_64; fi
rtsp_toolchain="$rtsp_ndk/toolchains/llvm/prebuilt/$rtsp_host/bin"
rtsp_root="$PWD"
mkdir -p tools/vendor
if [ ! -d tools/vendor/FFmpeg-n8.0.1 ]; then
 curl --connect-timeout 15 --max-time 180 -fL https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/tags/n8.0.1 -o tools/vendor/ffmpeg.tar.gz
 echo "679aa13a19415d5ddab91e580084e3ab20c963c8240001e5cbb955a97bdd81b1  tools/vendor/ffmpeg.tar.gz" | shasum -a 256 -c -
 tar -xf tools/vendor/ffmpeg.tar.gz -C tools/vendor
fi
for rtsp_abi in arm64-v8a x86_64; do
 if [ "$rtsp_abi" = arm64-v8a ]; then rtsp_arch=aarch64; rtsp_target=aarch64-linux-android; else rtsp_arch=x86_64; rtsp_target=x86_64-linux-android; fi
 rtsp_build="$rtsp_root/tools/vendor/build-$rtsp_abi"
 rtsp_prefix="$rtsp_root/tools/vendor/install-$rtsp_abi"
 mkdir -p "$rtsp_build" "$rtsp_root/media/src/main/jniLibs/$rtsp_abi"
 cd "$rtsp_build"
 rtsp_config_hash=$(shasum -a 256 "$rtsp_root/tools/build-native.sh" | awk '{print $1}')
 if [ ! -f config.h ] || [ ! -f .configure-hash ] || [ "$(cat .configure-hash)" != "$rtsp_config_hash" ]; then
 "$rtsp_root/tools/vendor/FFmpeg-n8.0.1/configure" --prefix="$rtsp_prefix" --target-os=android --arch="$rtsp_arch" --enable-cross-compile --cc="$rtsp_toolchain/${rtsp_target}29-clang" --ar="$rtsp_toolchain/llvm-ar" --ranlib="$rtsp_toolchain/llvm-ranlib" --strip="$rtsp_toolchain/llvm-strip" --disable-everything --disable-programs --disable-doc --disable-debug --disable-autodetect --disable-asm --enable-pic --enable-network --enable-protocol=file,tcp,udp,rtp --enable-demuxer=rtsp,mov --enable-muxer=mp4 --enable-decoder=h264,aac --enable-parser=h264,hevc,aac --enable-bsf=extract_extradata,aac_adtstoasc --extra-cflags=-fPIC
 printf '%s\n' "$rtsp_config_hash" > .configure-hash
 fi
 make -j8 > build.log 2>&1
 make install >> build.log 2>&1
 "$rtsp_toolchain/${rtsp_target}29-clang" -shared -fPIC -std=c11 -O2 -Wl,-z,max-page-size=16384 -I"$rtsp_prefix/include" "$rtsp_root/media/src/main/cpp/recorder.c" -L"$rtsp_prefix/lib" -lavformat -lavcodec -lavutil -lm -lz -o "$rtsp_root/media/src/main/jniLibs/$rtsp_abi/librtsp_recorder.so"
 cd "$rtsp_root"
done
