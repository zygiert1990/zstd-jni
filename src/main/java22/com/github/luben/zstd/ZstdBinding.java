package com.github.luben.zstd;

import com.github.luben.zstd.util.Native;

import org.jetbrains.annotations.NotNull;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;

/**
 * libzstd downcall bindings shared by the FFM implementations.
 * <p>
 * Package-private on purpose: this type exists only under
 * META-INF/versions/22, so making it public would give a JDK 22+ consumer an
 * API that no other runtime has.
 */
final class ZstdBinding {

    static {
        Native.load();
    }

    private ZstdBinding() {
    }

    private static final Linker LINKER = nativeLinker();

    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

    /* This binding assumes a 64-bit size_t. */
    private static final ValueLayout.OfLong C_SIZE_T =
            (ValueLayout.OfLong) LINKER.canonicalLayouts().get("size_t");

    private static Linker nativeLinker() {
        try {
            return Linker.nativeLinker();
        } catch (UnsupportedOperationException e) {
            /* Multi-Release dispatch is by JDK feature version alone, so a JDK 22+
             * platform that cannot do FFM still gets these classes rather than the
             * JNI ones. The property is what puts it back on the JNI
             * implementation; it is read once at startup and applies to every jar
             * in the JVM. Reached on JDKs built without libffi and without a
             * specialized linker - BellSoft's 32-bit builds, for instance. */
            throw new UnsupportedOperationException(
                    "The zstd-jni FFM implementation needs an FFM Linker, which this platform does not"
                            + " have. Start the JVM with -Djdk.util.jar.enableMultiRelease=false to use"
                            + " the JNI implementation instead.", e);
        }
    }

    /* ZSTD_EndDirective */
    static final int ZSTD_E_CONTINUE = 0;
    static final int ZSTD_E_FLUSH    = 1;
    static final int ZSTD_E_END      = 2;

    /* ZSTD_ResetDirective */
    static final int ZSTD_RESET_SESSION_ONLY = 1;

    private static final MethodHandle ZSTD_CStreamOutSize =
            downcall(
                    "ZSTD_CStreamOutSize",
                    FunctionDescriptor.of(C_SIZE_T),
                    MethodType.methodType(long.class));
    private static final MethodHandle ZSTD_createCStream =
            downcall(
                    "ZSTD_createCStream",
                    FunctionDescriptor.of(ValueLayout.ADDRESS),
                    MethodType.methodType(MemorySegment.class));
    private static final MethodHandle ZSTD_freeCStream =
            downcall(
                    "ZSTD_freeCStream",
                    FunctionDescriptor.of(C_SIZE_T, ValueLayout.ADDRESS),
                    MethodType.methodType(long.class, MemorySegment.class));
    private static final MethodHandle ZSTD_CCtx_reset =
            downcall(
                    "ZSTD_CCtx_reset",
                    FunctionDescriptor.of(C_SIZE_T, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                    MethodType.methodType(long.class, MemorySegment.class, int.class));

    /* Not plain ZSTD_compressStream2, which takes ZSTD_inBuffer / ZSTD_outBuffer:
     * under Linker.Option.critical - the FFM analogue of the JNI implementation's
     * GetPrimitiveArrayCritical - a heap byte[] can be passed as a pointer
     * argument but never stored into an off-heap struct, so the structs would
     * force a staging buffer and a copy of every byte both ways. zstd offers this
     * variant to "binders from dynamic languages which have troubles handling
     * structures containing memory pointers".
     */
    private static final MethodHandle ZSTD_compressStream2_simpleArgs =
            downcallCritical(
                    "ZSTD_compressStream2_simpleArgs",
                    FunctionDescriptor.of(
                            C_SIZE_T,
                            ValueLayout.ADDRESS,                                       // ZSTD_CCtx* cctx
                            ValueLayout.ADDRESS, C_SIZE_T, ValueLayout.ADDRESS,        // dst, dstCapacity, dstPos
                            ValueLayout.ADDRESS, C_SIZE_T, ValueLayout.ADDRESS,        // src, srcSize, srcPos
                            ValueLayout.JAVA_INT),                                     // ZSTD_EndDirective endOp
                    MethodType.methodType(long.class,
                            MemorySegment.class,
                            MemorySegment.class, long.class, MemorySegment.class,
                            MemorySegment.class, long.class, MemorySegment.class,
                            int.class));

    private static final MethodHandle ZSTD_DStreamInSize =
            downcall(
                    "ZSTD_DStreamInSize",
                    FunctionDescriptor.of(C_SIZE_T),
                    MethodType.methodType(long.class));
    private static final MethodHandle ZSTD_DStreamOutSize =
            downcall(
                    "ZSTD_DStreamOutSize",
                    FunctionDescriptor.of(C_SIZE_T),
                    MethodType.methodType(long.class));
    private static final MethodHandle ZSTD_createDStream =
            downcall(
                    "ZSTD_createDStream",
                    FunctionDescriptor.of(ValueLayout.ADDRESS),
                    MethodType.methodType(MemorySegment.class));
    /* ZSTD_freeDStream's counterpart, and the one the JNI implementation calls:
     * a ZSTD_DStream is a ZSTD_DCtx, and both functions free it the same way. */
    private static final MethodHandle ZSTD_freeDCtx =
            downcall(
                    "ZSTD_freeDCtx",
                    FunctionDescriptor.of(C_SIZE_T, ValueLayout.ADDRESS),
                    MethodType.methodType(long.class, MemorySegment.class));

    /* The decompression twin of ZSTD_compressStream2_simpleArgs, chosen for the
     * same reason: no ZSTD_inBuffer / ZSTD_outBuffer means every pointer argument
     * can be a heap byte[] under Linker.Option.critical. */
    private static final MethodHandle ZSTD_decompressStream_simpleArgs =
            downcallCritical(
                    "ZSTD_decompressStream_simpleArgs",
                    FunctionDescriptor.of(
                            C_SIZE_T,
                            ValueLayout.ADDRESS,                                       // ZSTD_DCtx* dctx
                            ValueLayout.ADDRESS, C_SIZE_T, ValueLayout.ADDRESS,        // dst, dstCapacity, dstPos
                            ValueLayout.ADDRESS, C_SIZE_T, ValueLayout.ADDRESS),       // src, srcSize, srcPos
                    MethodType.methodType(long.class,
                            MemorySegment.class,
                            MemorySegment.class, long.class, MemorySegment.class,
                            MemorySegment.class, long.class, MemorySegment.class));

    private static MethodHandle downcall(@NotNull String name,
                                         @NotNull FunctionDescriptor descriptor,
                                         @NotNull MethodType javaType) {
        return LINKER.downcallHandle(symbol(name), descriptor).asType(javaType);
    }

    private static MethodHandle downcallCritical(@NotNull String name,
                                                 @NotNull FunctionDescriptor descriptor,
                                                 @NotNull MethodType javaType) {
        return LINKER.downcallHandle(symbol(name), descriptor, Linker.Option.critical(true)).asType(javaType);
    }

    private static @NotNull MemorySegment symbol(@NotNull String name) {
        return LOOKUP.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Cannot find the symbol " + name));
    }

    /**
     * A {@code size_t*} in/out parameter backed by a one-element {@code long}
     * array, matching this binding's assumption that size_t is 64-bit.
     */
    abstract static class SizeTRef {

        /** The array, as a pointer argument. Built once; the array is final. */
        final @NotNull MemorySegment segment;

        private SizeTRef(@NotNull MemorySegment segment) {
            this.segment = segment;
        }

        abstract long get();

        abstract void set(long value);

        private static final class Wide extends SizeTRef {
            private final long[] cell;

            private Wide(long[] cell) {
                super(MemorySegment.ofArray(cell));
                this.cell = cell;
            }

            long get() {
                return cell[0];
            }

            void set(long value) {
                cell[0] = value;
            }
        }

    }

    static @NotNull SizeTRef newSizeTRef() {
        return new SizeTRef.Wide(new long[1]);
    }

    static long cStreamOutSize() {
        try {
            return (long) ZSTD_CStreamOutSize.invokeExact();
        } catch (Throwable t) {
            throw new AssertionError("Call to ZSTD_CStreamOutSize failed", t);
        }
    }

    static @NotNull MemorySegment createCStream() {
        try {
            return (MemorySegment) ZSTD_createCStream.invokeExact();
        } catch (Throwable t) {
            throw new AssertionError("Call to ZSTD_createCStream failed", t);
        }
    }

    static long freeCStream(@NotNull MemorySegment cctx) {
        try {
            return (long) ZSTD_freeCStream.invokeExact(cctx);
        } catch (Throwable t) {
            throw new AssertionError("Call to ZSTD_freeCStream failed", t);
        }
    }

    /** @param directive one of the ZSTD_RESET_* values */
    static long resetCCtx(@NotNull MemorySegment cctx, int directive) {
        try {
            return (long) ZSTD_CCtx_reset.invokeExact(cctx, directive);
        } catch (Throwable t) {
            throw new AssertionError("Call to ZSTD_CCtx_reset failed", t);
        }
    }

    /**
     * `srcSize` is an absolute end offset rather than a length, matching the way
     * the JNI implementation calls this: libzstd is handed the whole array and
     * reads from `srcPos` up to `srcSize`. Both position segments are in/out.
     *
     * @param endOp one of the ZSTD_E_* values
     */
    static long compressStream2(@NotNull MemorySegment cctx,
                                @NotNull MemorySegment dst, long dstCapacity, @NotNull MemorySegment dstPos,
                                @NotNull MemorySegment src, long srcSize, @NotNull MemorySegment srcPos,
                                int endOp) {
        try {
            return (long) ZSTD_compressStream2_simpleArgs.invokeExact(
                    cctx,
                    dst, dstCapacity, dstPos,
                    src, srcSize, srcPos,
                    endOp);
        } catch (Throwable t) {
            throw new AssertionError("Call to ZSTD_compressStream2_simpleArgs failed", t);
        }
    }

    static long dStreamInSize() {
        try {
            return (long) ZSTD_DStreamInSize.invokeExact();
        } catch (Throwable t) {
            throw new AssertionError("Call to ZSTD_DStreamInSize failed", t);
        }
    }

    static long dStreamOutSize() {
        try {
            return (long) ZSTD_DStreamOutSize.invokeExact();
        } catch (Throwable t) {
            throw new AssertionError("Call to ZSTD_DStreamOutSize failed", t);
        }
    }

    static @NotNull MemorySegment createDStream() {
        try {
            return (MemorySegment) ZSTD_createDStream.invokeExact();
        } catch (Throwable t) {
            throw new AssertionError("Call to ZSTD_createDStream failed", t);
        }
    }

    static long freeDCtx(@NotNull MemorySegment dctx) {
        try {
            return (long) ZSTD_freeDCtx.invokeExact(dctx);
        } catch (Throwable t) {
            throw new AssertionError("Call to ZSTD_freeDCtx failed", t);
        }
    }

    /**
     * Like {@link #compressStream2}, `dstCapacity` is an absolute end offset
     * rather than a length - libzstd gets the whole destination array and writes
     * from `dstPos` up to `dstCapacity`, as it does in the JNI implementation.
     * `srcSize` really is a length there: it is how many bytes the last upstream
     * read put at the front of the source buffer. Both position segments are
     * in/out.
     */
    static long decompressStream(@NotNull MemorySegment dctx,
                                 @NotNull MemorySegment dst, long dstCapacity, @NotNull MemorySegment dstPos,
                                 @NotNull MemorySegment src, long srcSize, @NotNull MemorySegment srcPos) {
        try {
            return (long) ZSTD_decompressStream_simpleArgs.invokeExact(
                    dctx,
                    dst, dstCapacity, dstPos,
                    src, srcSize, srcPos);
        } catch (Throwable t) {
            throw new AssertionError("Call to ZSTD_decompressStream_simpleArgs failed", t);
        }
    }
}
