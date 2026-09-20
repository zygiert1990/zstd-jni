#!/bin/bash

set -euo pipefail

readonly LIBERICA_VERSION="25.0.4+9"
readonly LIBERICA_BASE_NAME="bellsoft-jdk${LIBERICA_VERSION}"
readonly LIBERICA_RELEASE_URL="https://github.com/bell-sw/Liberica/releases/download/25.0.4%2B9"
readonly LIBERICA_JDK_NAME="${LIBERICA_BASE_NAME}-linux-arm32-vfp-hflt.tar.gz"
readonly LIBERICA_JDK_SHA256="904e9daaf438284998c95be22f2de7055fc381d3870d2d2e263aed0d40a0065f"
readonly LIBERICA_SOURCE_NAME="${LIBERICA_BASE_NAME}-src.tar.gz"
readonly LIBERICA_SOURCE_SHA256="21ae9fed223240e8b719415ce79d05815f64580b7ab8994af9a6eaf9cb148e50"

readonly FALLBACK_WORK_DIR="/tmp/fallback-linker"
readonly JDK_ARCHIVE="${FALLBACK_WORK_DIR}/${LIBERICA_JDK_NAME}"
readonly SOURCE_ARCHIVE="${FALLBACK_WORK_DIR}/${LIBERICA_SOURCE_NAME}"
readonly JDK_SOURCE_DIR="${FALLBACK_WORK_DIR}/jdk-source"
readonly FALLBACK_BUILD_DIR="${FALLBACK_WORK_DIR}/build"

apt-get update
apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    gcc \
    libffi-dev \
    pkg-config

mkdir -p \
    "${FALLBACK_WORK_DIR}" \
    "${JDK_SOURCE_DIR}" \
    "${FALLBACK_BUILD_DIR}/classes" \
    "${FALLBACK_BUILD_DIR}/headers"

curl --fail --location --retry 3 --retry-all-errors \
    --output "${JDK_ARCHIVE}" \
    "${LIBERICA_RELEASE_URL}/${LIBERICA_JDK_NAME}"
curl --fail --location --retry 3 --retry-all-errors \
    --output "${SOURCE_ARCHIVE}" \
    "${LIBERICA_RELEASE_URL}/${LIBERICA_SOURCE_NAME}"

printf '%s  %s\n' "${LIBERICA_JDK_SHA256}" "${JDK_ARCHIVE}" | sha256sum --check --strict -
printf '%s  %s\n' "${LIBERICA_SOURCE_SHA256}" "${SOURCE_ARCHIVE}" | sha256sum --check --strict -

export JAVA_HOME="${FALLBACK_WORK_DIR}/jdk"
export PATH="${JAVA_HOME}/bin:${PATH}"
mkdir -p "${JAVA_HOME}"
tar -xzf "${JDK_ARCHIVE}" --strip-components=1 -C "${JAVA_HOME}"

# Only these files are needed to build libfallbackLinker. Extracting selected
# paths keeps the full, matching BellSoft source archive as the authority
# without materializing the whole OpenJDK tree in the emulated guest.
tar -xzf "${SOURCE_ARCHIVE}" --strip-components=1 -C "${JDK_SOURCE_DIR}" \
    jdk-25.0.4/src/java.base/share/classes/jdk/internal/foreign/abi/fallback/LibFallback.java \
    jdk-25.0.4/src/java.base/share/native/libfallbackLinker/fallbackLinker.c \
    jdk-25.0.4/src/java.base/share/native/libjava/jlong.h \
    jdk-25.0.4/src/java.base/unix/native/libjava/jlong_md.h

java -version
if java -version 2>&1 | grep -q 'Zero VM'; then
    echo "Expected a JIT-enabled Liberica VM, but found Zero VM" >&2
    exit 1
fi

# Generate the JNI declarations expected by fallbackLinker.c. The generated
# classes are build-only; Liberica already contains the Java fallback linker.
javac \
    --patch-module "java.base=${JDK_SOURCE_DIR}/src/java.base/share/classes" \
    -h "${FALLBACK_BUILD_DIR}/headers" \
    -d "${FALLBACK_BUILD_DIR}/classes" \
    "${JDK_SOURCE_DIR}/src/java.base/share/classes/jdk/internal/foreign/abi/fallback/LibFallback.java"

read -r -a LIBFFI_CFLAGS <<< "$(pkg-config --cflags libffi)"
read -r -a LIBFFI_LIBS <<< "$(pkg-config --libs libffi)"

cc -O2 -fPIC -shared \
    -Wl,-z,defs \
    -Wl,-soname,libfallbackLinker.so \
    -I"${FALLBACK_BUILD_DIR}/headers" \
    -I"${JAVA_HOME}/include" \
    -I"${JAVA_HOME}/include/linux" \
    -I"${JDK_SOURCE_DIR}/src/java.base/share/native/libjava" \
    -I"${JDK_SOURCE_DIR}/src/java.base/unix/native/libjava" \
    "${LIBFFI_CFLAGS[@]}" \
    "${JDK_SOURCE_DIR}/src/java.base/share/native/libfallbackLinker/fallbackLinker.c" \
    "${LIBFFI_LIBS[@]}" \
    -o "${FALLBACK_BUILD_DIR}/libfallbackLinker.so"

install -m 0755 "${FALLBACK_BUILD_DIR}/libfallbackLinker.so" "${JAVA_HOME}/lib/"

pushd sbt-java-module-info
./sbt publishLocal
popd

# The 32-bit job exists to exercise FFM's FallbackLinker and 4-byte size_t.
# JNI coverage is provided by the other jobs.
./sbt -v testFromJarSetup \
         "testFromJar ${JAVA_HOME}"
