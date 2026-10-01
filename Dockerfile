# Hush toolchain image. Two targets, one file:
#   docker build --target train   -t hush-train   .
#   docker build --target android -t hush-android .
# Source is mounted at run time (not copied), so edits never need a rebuild:
#   docker run --rm -v "$PWD":/hush hush-train   python training/train.py
#   docker run --rm -v "$PWD":/hush hush-android ./gradlew assembleRelease

# ---------- Training: landmark extraction, model training, .tflite export ----------
# CPU-only on purpose: the lip model is ~350k params on landmark sequences, no GPU needed.
# No system ffmpeg: MediaPipe's OpenCV wheel bundles its own video decoder; libgl/glib are what it links against.
FROM python:3.11-slim AS train
RUN apt-get update && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir --extra-index-url https://download.pytorch.org/whl/cpu \
    torch mediapipe litert-torch numpy
WORKDIR /hush

# ---------- Android: build the APK without Android Studio ----------
FROM eclipse-temurin:17-jdk-jammy AS android
ARG CMDLINE_TOOLS=13114758
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
RUN apt-get update && apt-get install -y --no-install-recommends unzip wget \
    && rm -rf /var/lib/apt/lists/* \
    && wget -q https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip -O /tmp/t.zip \
    && mkdir -p $ANDROID_HOME/cmdline-tools \
    && unzip -q /tmp/t.zip -d $ANDROID_HOME/cmdline-tools \
    && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
    && rm /tmp/t.zip \
    && yes | sdkmanager --licenses > /dev/null \
    && sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
WORKDIR /hush/app
