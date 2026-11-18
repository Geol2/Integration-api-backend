#!/usr/bin/env bash
# 안경 마이크로 묻기용 음성 인식(whisper.cpp)을 서버에 설치합니다. 한 번만 실행하면 됩니다.
#
#   sudo bash scripts/install-whisper.sh          # small 모델(기본, 한국어 무난, 약 470MB)
#   sudo bash scripts/install-whisper.sh medium   # 더 정확하지만 느림(약 1.5GB) → AI_WHISPER_MODEL도 바꾸세요
#
# 설치 위치: /opt/whisper/bin/whisper-cli, /opt/whisper/models/ggml-<모델>.bin
# (application.yml 기본 경로와 같아서, small이면 Jenkins 설정을 바꿀 필요가 없습니다.)
# 설치 후 백엔드를 재시작(Jenkins 빌드)하면 로그에 "음성 인식(whisper) 사용"이 찍힙니다.
set -euo pipefail

MODEL="${1:-small}"
VERSION="v1.9.5"
PREFIX="/opt/whisper"

need() {
  command -v "$1" >/dev/null 2>&1 || { echo "먼저 설치하세요: $1  (예: sudo apt-get install -y $2)"; exit 1; }
}
need git git
need cmake cmake
need g++ build-essential
need curl curl

mkdir -p "$PREFIX/src" "$PREFIX/bin" "$PREFIX/models"

if [ ! -d "$PREFIX/src/whisper.cpp" ]; then
  git clone --depth 1 --branch "$VERSION" https://github.com/ggml-org/whisper.cpp "$PREFIX/src/whisper.cpp"
fi
cd "$PREFIX/src/whisper.cpp"
# 공유 라이브러리 없이 실행 파일 하나로 만들어, 어디로 옮겨도 돌게 합니다.
cmake -B build -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DWHISPER_BUILD_TESTS=OFF
cmake --build build -j"$(nproc)" --config Release --target whisper-cli
install -m 755 build/bin/whisper-cli "$PREFIX/bin/whisper-cli"

MODEL_FILE="$PREFIX/models/ggml-$MODEL.bin"
if [ ! -f "$MODEL_FILE" ]; then
  echo "모델 내려받는 중: ggml-$MODEL.bin"
  curl -L --fail -o "$MODEL_FILE.part" "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$MODEL.bin"
  mv "$MODEL_FILE.part" "$MODEL_FILE"
fi

# 백엔드(jenkins 사용자)가 읽고 실행할 수 있게
chmod -R a+rX "$PREFIX"

echo
echo "설치 완료. 동작 확인:"
echo "  $PREFIX/bin/whisper-cli -m $MODEL_FILE -f $PREFIX/src/whisper.cpp/samples/jfk.wav -nt"
if [ "$MODEL" != "small" ]; then
  echo "small이 아니므로 Jenkins 환경 변수에 AI_WHISPER_MODEL=$MODEL_FILE 를 넣으세요."
fi
