package net.lax1dude.eaglercraft.v2_6.internal.teavm;

import org.teavm.jso.JSMethod;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.typedarrays.Int32Array;
import org.teavm.jso.typedarrays.Uint8Array;

/**
 * Complete WebGL2 rendering context JSO interface for EaglerCraft 26.1.2.
 * Provides type-safe access to all WebGL2 constants and methods via TeaVM.
 * WebGL1 fallback is NOT supported in 26.1.2 - WebGL2 is required.
 */
public abstract class WebGL2RenderingContext implements JSObject {

        // ========== Clearing Masks ==========
        public static final int DEPTH_BUFFER_BIT = 0x00000100;
        public static final int STENCIL_BUFFER_BIT = 0x00000400;
        public static final int COLOR_BUFFER_BIT = 0x00004000;

        // ========== Begin/End Mode ==========
        public static final int POINTS = 0x0000;
        public static final int LINES = 0x0001;
        public static final int LINE_LOOP = 0x0002;
        public static final int LINE_STRIP = 0x0003;
        public static final int TRIANGLES = 0x0004;
        public static final int TRIANGLE_STRIP = 0x0005;
        public static final int TRIANGLE_FAN = 0x0006;

        // ========== Alpha Blending ==========
        public static final int ZERO = 0;
        public static final int ONE = 1;
        public static final int SRC_COLOR = 0x0300;
        public static final int ONE_MINUS_SRC_COLOR = 0x0301;
        public static final int SRC_ALPHA = 0x0302;
        public static final int ONE_MINUS_SRC_ALPHA = 0x0303;
        public static final int DST_ALPHA = 0x0304;
        public static final int ONE_MINUS_DST_ALPHA = 0x0305;
        public static final int DST_COLOR = 0x0306;
        public static final int ONE_MINUS_DST_COLOR = 0x0307;
        public static final int SRC_ALPHA_SATURATE = 0x0308;
        public static final int CONSTANT_COLOR = 0x8001;
        public static final int ONE_MINUS_CONSTANT_COLOR = 0x8002;
        public static final int CONSTANT_ALPHA = 0x8003;
        public static final int ONE_MINUS_CONSTANT_ALPHA = 0x8004;

        // ========== Blend Equations ==========
        public static final int FUNC_ADD = 0x8006;
        public static final int FUNC_SUBTRACT = 0x800A;
        public static final int FUNC_REVERSE_SUBTRACT = 0x800B;
        public static final int MIN = 0x8007;
        public static final int MAX = 0x8008;

        // ========== Buffer Objects ==========
        public static final int ARRAY_BUFFER = 0x8892;
        public static final int ELEMENT_ARRAY_BUFFER = 0x8893;
        public static final int COPY_READ_BUFFER = 0x8F36;
        public static final int COPY_WRITE_BUFFER = 0x8F37;
        public static final int PIXEL_PACK_BUFFER = 0x88EB;
        public static final int PIXEL_UNPACK_BUFFER = 0x88EC;
        public static final int TRANSFORM_FEEDBACK_BUFFER = 0x8C8E;
        public static final int UNIFORM_BUFFER = 0x8A11;
        public static final int TEXTURE_BUFFER = 0x8C2A;

        public static final int STREAM_DRAW = 0x88E0;
        public static final int STATIC_DRAW = 0x88E4;
        public static final int DYNAMIC_DRAW = 0x88E8;
        public static final int STREAM_READ = 0x88E1;
        public static final int STATIC_READ = 0x88E5;
        public static final int DYNAMIC_READ = 0x88E9;
        public static final int STREAM_COPY = 0x88E2;
        public static final int STATIC_COPY = 0x88E6;
        public static final int DYNAMIC_COPY = 0x88EA;

        // ========== Buffer Access ==========
        public static final int BUFFER_SIZE = 0x8764;
        public static final int BUFFER_USAGE = 0x8765;
        public static final int BUFFER_ACCESS = 0x88BB;
        public static final int BUFFER_MAPPED = 0x88BC;
        public static final int BUFFER_MAP_POINTER = 0x88BD;
        public static final int ACCESS_READ_ONLY = 0x88B8;
        public static final int ACCESS_WRITE_ONLY = 0x88B9;
        public static final int ACCESS_READ_WRITE = 0x88BA;

        // ========== Shaders ==========
        public static final int FRAGMENT_SHADER = 0x8B30;
        public static final int VERTEX_SHADER = 0x8B31;
        public static final int GEOMETRY_SHADER = 0x8DD9;
        public static final int TESS_EVALUATION_SHADER = 0x8E87;
        public static final int TESS_CONTROL_SHADER = 0x8E88;
        public static final int COMPUTE_SHADER = 0x91B9;

        public static final int COMPILE_STATUS = 0x8B81;
        public static final int DELETE_STATUS = 0x8B80;
        public static final int LINK_STATUS = 0x8B82;
        public static final int VALIDATE_STATUS = 0x8B83;
        public static final int INFO_LOG_LENGTH = 0x8B84;
        public static final int ATTACHED_SHADERS = 0x8B85;
        public static final int ACTIVE_UNIFORMS = 0x8B86;
        public static final int ACTIVE_ATTRIBUTES = 0x8B89;
        public static final int SHADER_TYPE = 0x8B4F;
        public static final int SHADER_SOURCE_LENGTH = 0x8B88;

        // ========== Data Types ==========
        public static final int FLOAT = 0x1406;
        public static final int FLOAT_VEC2 = 0x8B50;
        public static final int FLOAT_VEC3 = 0x8B51;
        public static final int FLOAT_VEC4 = 0x8B52;
        public static final int INT = 0x1404;
        public static final int INT_VEC2 = 0x8B53;
        public static final int INT_VEC3 = 0x8B54;
        public static final int INT_VEC4 = 0x8B55;
        public static final int BOOL = 0x8B56;
        public static final int BOOL_VEC2 = 0x8B57;
        public static final int BOOL_VEC3 = 0x8B58;
        public static final int BOOL_VEC4 = 0x8B59;
        public static final int FLOAT_MAT2 = 0x8B5A;
        public static final int FLOAT_MAT3 = 0x8B5B;
        public static final int FLOAT_MAT4 = 0x8B5C;
        public static final int SAMPLER_2D = 0x8B5E;
        public static final int SAMPLER_CUBE = 0x8B60;
        public static final int SAMPLER_3D = 0x8B5F;
        public static final int SAMPLER_2D_SHADOW = 0x8B62;
        public static final int SAMPLER_2D_ARRAY = 0x8DC1;
        public static final int SAMPLER_2D_ARRAY_SHADOW = 0x8DC4;
        public static final int SAMPLER_CUBE_SHADOW = 0x8DC5;
        public static final int INT_SAMPLER_2D = 0x8DCA;
        public static final int INT_SAMPLER_3D = 0x8DCB;
        public static final int INT_SAMPLER_CUBE = 0x8DCC;
        public static final int INT_SAMPLER_2D_ARRAY = 0x8DCF;
        public static final int UNSIGNED_INT_SAMPLER_2D = 0x8DD2;
        public static final int UNSIGNED_INT_SAMPLER_3D = 0x8DD3;
        public static final int UNSIGNED_INT_SAMPLER_CUBE = 0x8DD4;
        public static final int UNSIGNED_INT_SAMPLER_2D_ARRAY = 0x8DD7;
        public static final int FLOAT_MAT2x3 = 0x8B65;
        public static final int FLOAT_MAT2x4 = 0x8B66;
        public static final int FLOAT_MAT3x2 = 0x8B67;
        public static final int FLOAT_MAT3x4 = 0x8B68;
        public static final int FLOAT_MAT4x2 = 0x8B69;
        public static final int FLOAT_MAT4x3 = 0x8B6A;
        public static final int UNSIGNED_INT = 0x1405;
        public static final int UNSIGNED_INT_VEC2 = 0x8DC6;
        public static final int UNSIGNED_INT_VEC3 = 0x8DC7;
        public static final int UNSIGNED_INT_VEC4 = 0x8DC8;

        // ========== Texture ==========
        public static final int TEXTURE_2D = 0x0DE1;
        public static final int TEXTURE_3D = 0x806F;
        public static final int TEXTURE_CUBE_MAP = 0x8513;
        public static final int TEXTURE_2D_ARRAY = 0x8C1A;

        public static final int TEXTURE0 = 0x84C0;
        public static final int TEXTURE1 = 0x84C1;
        public static final int TEXTURE2 = 0x84C2;
        public static final int TEXTURE3 = 0x84C3;
        public static final int TEXTURE4 = 0x84C4;
        public static final int TEXTURE5 = 0x84C5;
        public static final int TEXTURE6 = 0x84C6;
        public static final int TEXTURE7 = 0x84C7;
        public static final int TEXTURE8 = 0x84C8;
        public static final int TEXTURE9 = 0x84C9;
        public static final int TEXTURE10 = 0x84CA;
        public static final int TEXTURE11 = 0x84CB;
        public static final int TEXTURE12 = 0x84CC;
        public static final int TEXTURE13 = 0x84CD;
        public static final int TEXTURE14 = 0x84CE;
        public static final int TEXTURE15 = 0x84CF;

        public static final int NEAREST = 0x2600;
        public static final int LINEAR = 0x2601;
        public static final int NEAREST_MIPMAP_NEAREST = 0x2700;
        public static final int LINEAR_MIPMAP_NEAREST = 0x2701;
        public static final int NEAREST_MIPMAP_LINEAR = 0x2702;
        public static final int LINEAR_MIPMAP_LINEAR = 0x2703;

        public static final int TEXTURE_MAG_FILTER = 0x2800;
        public static final int TEXTURE_MIN_FILTER = 0x2801;
        public static final int TEXTURE_WRAP_S = 0x2802;
        public static final int TEXTURE_WRAP_T = 0x2803;
        public static final int TEXTURE_WRAP_R = 0x8072;
        public static final int TEXTURE_MIN_LOD = 0x813A;
        public static final int TEXTURE_MAX_LOD = 0x813B;
        public static final int TEXTURE_BASE_LEVEL = 0x813C;
        public static final int TEXTURE_MAX_LEVEL = 0x813D;
        public static final int TEXTURE_COMPARE_MODE = 0x884C;
        public static final int TEXTURE_COMPARE_FUNC = 0x884D;
        public static final int TEXTURE_IMMUTABLE_FORMAT = 0x912F;
        public static final int TEXTURE_IMMUTABLE_LEVELS = 0x82DF;

        public static final int REPEAT = 0x2901;
        public static final int CLAMP_TO_EDGE = 0x812F;
        public static final int MIRRORED_REPEAT = 0x8370;

        public static final int R8 = 0x8229;
        public static final int R8_SNORM = 0x8F94;
        public static final int R16F = 0x822D;
        public static final int R32F = 0x822E;
        public static final int R8UI = 0x8232;
        public static final int R8I = 0x8231;
        public static final int R16UI = 0x8234;
        public static final int R16I = 0x8233;
        public static final int R32UI = 0x8236;
        public static final int R32I = 0x8235;
        public static final int RG8 = 0x822B;
        public static final int RG8_SNORM = 0x8F95;
        public static final int RG16F = 0x822F;
        public static final int RG32F = 0x8230;
        public static final int RG8UI = 0x8238;
        public static final int RG8I = 0x8237;
        public static final int RG16UI = 0x823A;
        public static final int RG16I = 0x8239;
        public static final int RG32UI = 0x823C;
        public static final int RG32I = 0x823B;
        public static final int RGB8 = 0x8051;
        public static final int RGB8_SNORM = 0x8F96;
        public static final int RGB16F = 0x881B;
        public static final int RGB32F = 0x8815;
        public static final int RGB565 = 0x8D62;
        public static final int RGB9_E5 = 0x8C3D;
        public static final int RGBA8 = 0x8058;
        public static final int RGBA8_SNORM = 0x8F97;
        public static final int RGBA16F = 0x881A;
        public static final int RGBA32F = 0x8814;
        public static final int RGB5_A1 = 0x8057;
        public static final int RGBA4 = 0x8056;
        public static final int RGB10_A2 = 0x8059;
        public static final int RGB10_A2UI = 0x906F;
        public static final int SRGB8 = 0x8C41;
        public static final int SRGB8_ALPHA8 = 0x8C43;
        public static final int R11F_G11F_B10F = 0x8C3A;
        public static final int DEPTH_COMPONENT16 = 0x81A5;
        public static final int DEPTH_COMPONENT24 = 0x81A6;
        public static final int DEPTH_COMPONENT32F = 0x8CAC;
        public static final int DEPTH24_STENCIL8 = 0x88F0;
        public static final int DEPTH32F_STENCIL8 = 0x8CAD;
        public static final int STENCIL_INDEX8 = 0x8D48;

        public static final int RED = 0x1903;
        public static final int RG = 0x8227;
        public static final int RGB = 0x1907;
        public static final int RGBA = 0x1908;
        public static final int RED_INTEGER = 0x8D94;
        public static final int RG_INTEGER = 0x8228;
        public static final int RGB_INTEGER = 0x8D98;
        public static final int RGBA_INTEGER = 0x8D99;
        public static final int DEPTH_COMPONENT = 0x1902;
        public static final int DEPTH_STENCIL = 0x84F9;
        public static final int LUMINANCE = 0x1909;
        public static final int LUMINANCE_ALPHA = 0x190A;

        public static final int UNSIGNED_BYTE = 0x1401;
        public static final int BYTE = 0x1400;
        public static final int UNSIGNED_SHORT = 0x1403;
        public static final int SHORT = 0x1402;
        public static final int HALF_FLOAT = 0x140B;
        public static final int UNSIGNED_SHORT_5_6_5 = 0x8363;
        public static final int UNSIGNED_SHORT_4_4_4_4 = 0x8033;
        public static final int UNSIGNED_SHORT_5_5_5_1 = 0x8034;
        public static final int UNSIGNED_INT_2_10_10_10_REV = 0x8368;
        public static final int UNSIGNED_INT_10F_11F_11F_REV = 0x8C3B;
        public static final int UNSIGNED_INT_24_8 = 0x84FA;
        public static final int FLOAT_32_UNSIGNED_INT_24_8_REV = 0x8DAD;

        // ========== Framebuffer ==========
        public static final int FRAMEBUFFER = 0x8D40;
        public static final int READ_FRAMEBUFFER = 0x8CA8;
        public static final int DRAW_FRAMEBUFFER = 0x8CA9;
        public static final int RENDERBUFFER = 0x8D41;

        public static final int COLOR_ATTACHMENT0 = 0x8CE0;
        public static final int COLOR_ATTACHMENT1 = 0x8CE1;
        public static final int COLOR_ATTACHMENT2 = 0x8CE2;
        public static final int COLOR_ATTACHMENT3 = 0x8CE3;
        public static final int COLOR_ATTACHMENT4 = 0x8CE4;
        public static final int COLOR_ATTACHMENT5 = 0x8CE5;
        public static final int COLOR_ATTACHMENT6 = 0x8CE6;
        public static final int COLOR_ATTACHMENT7 = 0x8CE7;
        public static final int COLOR_ATTACHMENT8 = 0x8CE8;
        public static final int COLOR_ATTACHMENT9 = 0x8CE9;
        public static final int COLOR_ATTACHMENT10 = 0x8CEA;
        public static final int COLOR_ATTACHMENT11 = 0x8CEB;
        public static final int COLOR_ATTACHMENT12 = 0x8CEC;
        public static final int COLOR_ATTACHMENT13 = 0x8CED;
        public static final int COLOR_ATTACHMENT14 = 0x8CEE;
        public static final int COLOR_ATTACHMENT15 = 0x8CEF;
        public static final int DEPTH_ATTACHMENT = 0x8D00;
        public static final int STENCIL_ATTACHMENT = 0x8D20;
        public static final int DEPTH_STENCIL_ATTACHMENT = 0x821A;

        public static final int FRAMEBUFFER_COMPLETE = 0x8CD5;
        public static final int FRAMEBUFFER_INCOMPLETE_ATTACHMENT = 0x8CD6;
        public static final int FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT = 0x8CD7;
        public static final int FRAMEBUFFER_INCOMPLETE_DIMENSIONS = 0x8CD9;
        public static final int FRAMEBUFFER_UNSUPPORTED = 0x8CDD;
        public static final int FRAMEBUFFER_INCOMPLETE_MULTISAMPLE = 0x8D56;

        public static final int RENDERBUFFER_WIDTH = 0x8D42;
        public static final int RENDERBUFFER_HEIGHT = 0x8D43;
        public static final int RENDERBUFFER_INTERNAL_FORMAT = 0x8D44;
        public static final int RENDERBUFFER_RED_SIZE = 0x8D50;
        public static final int RENDERBUFFER_GREEN_SIZE = 0x8D51;
        public static final int RENDERBUFFER_BLUE_SIZE = 0x8D52;
        public static final int RENDERBUFFER_ALPHA_SIZE = 0x8D53;
        public static final int RENDERBUFFER_DEPTH_SIZE = 0x8D54;
        public static final int RENDERBUFFER_STENCIL_SIZE = 0x8D55;
        public static final int RENDERBUFFER_SAMPLES = 0x8CAB;

        // ========== Pixel Store ==========
        public static final int UNPACK_ALIGNMENT = 0x0CF5;
        public static final int PACK_ALIGNMENT = 0x0D05;
        public static final int UNPACK_ROW_LENGTH = 0x0CF2;
        public static final int UNPACK_SKIP_ROWS = 0x0CF3;
        public static final int UNPACK_SKIP_PIXELS = 0x0CF4;
        public static final int UNPACK_IMAGE_HEIGHT = 0x806E;
        public static final int UNPACK_SKIP_IMAGES = 0x806D;
        public static final int UNPACK_FLIP_Y_WEBGL = 0x9240;
        public static final int UNPACK_PREMULTIPLY_ALPHA_WEBGL = 0x9241;
        public static final int UNPACK_COLORSPACE_CONVERSION_WEBGL = 0x9243;
        public static final int PACK_ROW_LENGTH = 0x0D02;
        public static final int PACK_SKIP_ROWS = 0x0D03;
        public static final int PACK_SKIP_PIXELS = 0x0D04;

        // ========== Depth / Stencil ==========
        public static final int NEVER = 0x0200;
        public static final int LESS = 0x0201;
        public static final int EQUAL = 0x0202;
        public static final int LEQUAL = 0x0203;
        public static final int GREATER = 0x0204;
        public static final int NOTEQUAL = 0x0205;
        public static final int GEQUAL = 0x0206;
        public static final int ALWAYS = 0x0207;

        public static final int KEEP = 0x1E00;
        public static final int REPLACE = 0x1E01;
        public static final int INCR = 0x1E02;
        public static final int DECR = 0x1E03;
        public static final int INVERT = 0x150A;
        public static final int INCR_WRAP = 0x8507;
        public static final int DECR_WRAP = 0x8508;

        // ========== Enable / Disable Caps ==========
        public static final int CULL_FACE = 0x0B44;
        public static final int BLEND = 0x0BE2;
        public static final int DITHER = 0x0BD0;
        public static final int STENCIL_TEST = 0x0B90;
        public static final int DEPTH_TEST = 0x0B71;
        public static final int SCISSOR_TEST = 0x0C11;
        public static final int POLYGON_OFFSET_FILL = 0x8037;
        public static final int SAMPLE_ALPHA_TO_COVERAGE = 0x809F;
        public static final int SAMPLE_COVERAGE = 0x80A0;
        public static final int PRIMITIVE_RESTART_FIXED_INDEX = 0x8D69;
        public static final int RASTERIZER_DISCARD = 0x8C89;

        // ========== Hints ==========
        public static final int DONT_CARE = 0x1100;
        public static final int FASTEST = 0x1101;
        public static final int NICEST = 0x1102;
        public static final int GENERATE_MIPMAP_HINT = 0x8192;
        public static final int FRAGMENT_SHADER_DERIVATIVE_HINT = 0x8B8B;

        // ========== Face Culling ==========
        public static final int FRONT = 0x0404;
        public static final int BACK = 0x0405;
        public static final int FRONT_AND_BACK = 0x0408;
        public static final int CW = 0x0900;
        public static final int CCW = 0x0901;

        // ========== Error ==========
        public static final int NO_ERROR = 0;
        public static final int INVALID_ENUM = 0x0500;
        public static final int INVALID_VALUE = 0x0501;
        public static final int INVALID_OPERATION = 0x0502;
        public static final int OUT_OF_MEMORY = 0x0505;
        public static final int CONTEXT_LOST_WEBGL = 0x9242;

        // ========== Transform Feedback ==========
        public static final int TRANSFORM_FEEDBACK_VARYINGS = 0x8F45;
        public static final int TRANSFORM_FEEDBACK_BUFFER_MODE = 0x8F47;
        public static final int INTERLEAVED_ATTRIBS = 0x8C8C;
        public static final int SEPARATE_ATTRIBS = 0x8C8D;
        public static final int TRANSFORM_FEEDBACK_BUFFER_START = 0x8D84;
        public static final int TRANSFORM_FEEDBACK_BUFFER_SIZE = 0x8D85;
        public static final int TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN = 0x8C88;

        // ========== Uniform Buffer ==========
        public static final int UNIFORM_BUFFER_UNIFROM_BLOCK_INDEX = 0x8DC9;
        public static final int UNIFORM_BLOCK_DATA_SIZE = 0x8A40;
        public static final int UNIFORM_BLOCK_BINDING = 0x8A3F;
        public static final int UNIFORM_BUFFER_START = 0x8A29;
        public static final int UNIFORM_BUFFER_SIZE = 0x8A2A;
        public static final int MAX_UNIFORM_BLOCK_SIZE = 0x8A33;
        public static final int MAX_UNIFORM_BUFFER_BINDINGS = 0x8A2F;
        public static final int MAX_COMBINED_UNIFORM_BLOCKS = 0x8A2E;
        public static final int UNIFORM_OFFSET = 0x8A3B;
        public static final int UNIFORM_ARRAY_STRIDE = 0x8A3C;
        public static final int UNIFORM_MATRIX_STRIDE = 0x8A3D;
        public static final int UNIFORM_IS_ROW_MAJOR = 0x8A3E;

        // ========== Query Objects ==========
        public static final int ANY_SAMPLES_PASSED = 0x8C2F;
        public static final int ANY_SAMPLES_PASSED_CONSERVATIVE = 0x8D6A;
        public static final int TRANSFORM_FEEDBACK_OVERFLOW = 0x82ED;
        public static final int QUERY_RESULT = 0x8866;
        public static final int QUERY_RESULT_AVAILABLE = 0x8867;

        // ========== Sampler Objects ==========
        public static final int SAMPLER_BINDING = 0x8919;

        // ========== Sync Objects ==========
        public static final int SYNC_GPU_COMMANDS_COMPLETE = 0x9117;
        public static final int SYNC_FENCE = 0x9116;
        public static final int UNSIGNALED = 0x9118;
        public static final int SIGNALED = 0x9119;
        public static final int ALREADY_SIGNALED = 0x911A;
        public static final int TIMEOUT_EXPIRED = 0x911B;
        public static final int CONDITION_SATISFIED = 0x911C;
        public static final int WAIT_FAILED = 0x911D;
        public static final int OBJECT_TYPE = 0x9112;
        public static final int SYNC_STATUS = 0x9114;
        public static final int SYNC_CONDITION = 0x9113;
        public static final int SYNC_FLUSH_COMMANDS_BIT = 0x00000001;

        // ========== Draw Buffers ==========
        public static final int NONE = 0;
        public static final int COLOR = 0x1800;
        public static final int DEPTH = 0x1801;
        public static final int STENCIL = 0x1802;
        public static final int MAX_DRAW_BUFFERS = 0x8824;
        public static final int DRAW_BUFFER0 = 0x8825;
        public static final int DRAW_BUFFER1 = 0x8826;
        public static final int DRAW_BUFFER2 = 0x8827;
        public static final int DRAW_BUFFER3 = 0x8828;
        public static final int DRAW_BUFFER4 = 0x8829;
        public static final int DRAW_BUFFER5 = 0x882A;
        public static final int DRAW_BUFFER6 = 0x882B;
        public static final int DRAW_BUFFER7 = 0x882C;

        // ========== Multisample ==========
        public static final int SAMPLES = 0x80A9;
        public static final int MAX_SAMPLES = 0x8D57;

        // ========== Pixel Store for Buffer ==========
        public static final int PIXEL_PACK_BUFFER_BINDING = 0x88ED;
        public static final int PIXEL_UNPACK_BUFFER_BINDING = 0x88EF;

        // ========== Internal Format Info ==========
        public static final int INTERNALFORMAT_SUPPORTED = 0x8C6B;
        public static final int INTERNALFORMAT_PREFERRED = 0x8C70;
        public static final int INTERNALFORMAT_RED_SIZE = 0x8C76;
        public static final int INTERNALFORMAT_GREEN_SIZE = 0x8C77;
        public static final int INTERNALFORMAT_BLUE_SIZE = 0x8C78;
        public static final int INTERNALFORMAT_ALPHA_SIZE = 0x8C79;
        public static final int INTERNALFORMAT_DEPTH_SIZE = 0x8C7A;
        public static final int INTERNALFORMAT_STENCIL_SIZE = 0x8C7B;

        // ========== Miscellaneous ==========
        public static final int MAX_TEXTURE_SIZE = 0x0D33;
        public static final int MAX_CUBE_MAP_TEXTURE_SIZE = 0x851C;
        public static final int MAX_TEXTURE_IMAGE_UNITS = 0x8872;
        public static final int MAX_VERTEX_TEXTURE_IMAGE_UNITS = 0x8B4C;
        public static final int MAX_COMBINED_TEXTURE_IMAGE_UNITS = 0x8B4D;
        public static final int MAX_RENDERBUFFER_SIZE = 0x84E8;
        public static final int MAX_VERTEX_ATTRIBS = 0x8869;
        public static final int MAX_VERTEX_UNIFORM_VECTORS = 0x8DFB;
        public static final int MAX_VARYING_VECTORS = 0x8DFC;
        public static final int MAX_FRAGMENT_UNIFORM_VECTORS = 0x8DFD;
        public static final int MAX_VERTEX_OUTPUT_COMPONENTS = 0x9122;
        public static final int MAX_FRAGMENT_INPUT_COMPONENTS = 0x9125;
        public static final int MAX_VERTEX_UNIFORM_BLOCKS = 0x9123;
        public static final int MAX_FRAGMENT_UNIFORM_BLOCKS = 0x9125;
        public static final int MAX_3D_TEXTURE_SIZE = 0x8073;
        public static final int MAX_ARRAY_TEXTURE_LAYERS = 0x88FF;
        public static final int MAX_ELEMENTS_VERTICES = 0x80E8;
        public static final int MAX_ELEMENTS_INDICES = 0x80E9;
        public static final int MAX_VARYING_COMPONENTS = 0x8B4B;
        public static final int MAX_SERVER_WAIT_TIMEOUT = 0x9111;
        public static final int MAX_ELEMENT_INDEX = 0x8D6B;
        public static final int MAX_COMBINED_VERTEX_UNIFORM_COMPONENTS = 0x8A31;
        public static final int MAX_COMBINED_FRAGMENT_UNIFORM_COMPONENTS = 0x8A33;
        public static final int SUBPIXEL_BITS = 0x0D50;
        public static final int RED_BITS = 0x0D52;
        public static final int GREEN_BITS = 0x0D53;
        public static final int BLUE_BITS = 0x0D54;
        public static final int ALPHA_BITS = 0x0D55;
        public static final int DEPTH_BITS = 0x0D56;
        public static final int STENCIL_BITS = 0x0D57;
        public static final int ALIASED_LINE_WIDTH_RANGE = 0x846E;
        public static final int ALIASED_POINT_SIZE_RANGE = 0x846D;

        // ========== Get Parameter PNames ==========
        public static final int VERSION = 0x1F02;
        public static final int SHADING_LANGUAGE_VERSION = 0x8B8C;
        public static final int VENDOR = 0x1F00;
        public static final int RENDERER = 0x1F01;

        // ==================== Core WebGL2 Methods ====================

        // ----- Context State -----
        @JSMethod
        public abstract void viewport(int x, int y, int width, int height);

        @JSMethod
        public abstract void scissor(int x, int y, int width, int height);

        @JSMethod
        public abstract void clearColor(float red, float green, float blue, float alpha);

        @JSMethod
        public abstract void clearDepth(double depth);

        @JSMethod
        public abstract void clearStencil(int s);

        @JSMethod
        public abstract void clear(int mask);

        @JSMethod
        public abstract void enable(int cap);

        @JSMethod
        public abstract void disable(int cap);

        @JSMethod
        public abstract boolean isEnabled(int cap);

        @JSMethod
        public abstract int getError();

        @JSMethod
        public abstract JSObject getParameter(int pname);

        @JSMethod
        public abstract String getString(int pname);
        public abstract void hint(int target, int mode);

        // ----- Blending -----
        @JSMethod
        public abstract void blendFunc(int sfactor, int dfactor);

        @JSMethod
        public abstract void blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha);

        @JSMethod
        public abstract void blendEquation(int mode);

        @JSMethod
        public abstract void blendEquationSeparate(int modeRGB, int modeAlpha);

        @JSMethod
        public abstract void blendColor(float red, float green, float blue, float alpha);

        // ----- Depth / Stencil -----
        @JSMethod
        public abstract void depthFunc(int func);

        @JSMethod
        public abstract void depthMask(boolean flag);

        @JSMethod
        public abstract void depthRange(double zNear, double zFar);

        @JSMethod
        public abstract void stencilFunc(int func, int ref, int mask);

        @JSMethod
        public abstract void stencilFuncSeparate(int face, int func, int ref, int mask);

        @JSMethod
        public abstract void stencilOp(int fail, int zfail, int zpass);

        @JSMethod
        public abstract void stencilOpSeparate(int face, int sfail, int dpfail, int dppass);

        @JSMethod
        public abstract void stencilMask(int mask);

        @JSMethod
        public abstract void stencilMaskSeparate(int face, int mask);

        // ----- Color Mask / Dither -----
        @JSMethod
        public abstract void colorMask(boolean red, boolean green, boolean blue, boolean alpha);

        @JSMethod
        public abstract void sampleCoverage(float value, boolean invert);

        // ----- Face Culling -----
        @JSMethod
        public abstract void cullFace(int mode);

        @JSMethod
        public abstract void frontFace(int mode);

        @JSMethod
        public abstract void polygonOffset(float factor, float units);

        @JSMethod
        public abstract void lineWidth(float width);

        // ----- Shader Objects -----
        @JSMethod
        public abstract JSObject createShader(int type);

        @JSMethod
        public abstract void shaderSource(JSObject shader, String source);

        @JSMethod
        public abstract void compileShader(JSObject shader);

        @JSMethod
        public abstract void deleteShader(JSObject shader);

        @JSMethod
        public abstract int getShaderParameter(JSObject shader, int pname);

        @JSMethod
        public abstract String getShaderInfoLog(JSObject shader);

        @JSMethod
        public abstract String getShaderSource(JSObject shader);

        // ----- Program Objects -----
        @JSMethod
        public abstract JSObject createProgram();

        @JSMethod
        public abstract void attachShader(JSObject program, JSObject shader);

        @JSMethod
        public abstract void detachShader(JSObject program, JSObject shader);

        @JSMethod
        public abstract void linkProgram(JSObject program);

        @JSMethod
        public abstract void useProgram(JSObject program);

        @JSMethod
        public abstract void deleteProgram(JSObject program);

        @JSMethod
        public abstract void validateProgram(JSObject program);

        @JSMethod
        public abstract int getProgramParameter(JSObject program, int pname);

        @JSMethod
        public abstract String getProgramInfoLog(JSObject program);

        @JSMethod
        public abstract int getAttribLocation(JSObject program, String name);

        @JSMethod
        public abstract JSObject getActiveAttrib(JSObject program, int index);

        @JSMethod
        public abstract JSObject getActiveUniform(JSObject program, int index);

        @JSMethod
        public abstract JSObject getActiveUniformBlockName(JSObject program, int uniformBlockIndex);

        @JSMethod
        public abstract JSObject getActiveUniformBlockParameter(JSObject program, int uniformBlockIndex, int pname);

        @JSMethod
        public abstract int getUniformBlockIndex(JSObject program, String uniformBlockName);

        @JSMethod
        public abstract JSObject getActiveUniforms(JSObject program, int uniformIndices, int pname);

        @JSMethod
        public abstract int getUniformOffset(int program, int uniformIndex);

        @JSMethod
        public abstract JSObject getUniform(JSObject program, JSObject location);

        @JSMethod
        public abstract JSObject getUniformLocation(JSObject program, String name);

        // ----- Uniforms -----
        @JSMethod
        public abstract void uniform1f(JSObject location, float x);

        @JSMethod
        public abstract void uniform2f(JSObject location, float x, float y);

        @JSMethod
        public abstract void uniform3f(JSObject location, float x, float y, float z);

        @JSMethod
        public abstract void uniform4f(JSObject location, float x, float y, float z, float w);

        @JSMethod
        public abstract void uniform1i(JSObject location, int x);

        @JSMethod
        public abstract void uniform2i(JSObject location, int x, int y);

        @JSMethod
        public abstract void uniform3i(JSObject location, int x, int y, int z);

        @JSMethod
        public abstract void uniform4i(JSObject location, int x, int y, int z, int w);

        @JSMethod
        public abstract void uniform1ui(JSObject location, int x);

        @JSMethod
        public abstract void uniform2ui(JSObject location, int x, int y);

        @JSMethod
        public abstract void uniform3ui(JSObject location, int x, int y, int z);

        @JSMethod
        public abstract void uniform4ui(JSObject location, int x, int y, int z, int w);

        @JSMethod
        public abstract void uniform1fv(JSObject location, Float32Array v);

        @JSMethod
        public abstract void uniform2fv(JSObject location, Float32Array v);

        @JSMethod
        public abstract void uniform3fv(JSObject location, Float32Array v);

        @JSMethod
        public abstract void uniform4fv(JSObject location, Float32Array v);

        @JSMethod
        public abstract void uniform1iv(JSObject location, Int32Array v);

        @JSMethod
        public abstract void uniform2iv(JSObject location, Int32Array v);

        @JSMethod
        public abstract void uniform3iv(JSObject location, Int32Array v);

        @JSMethod
        public abstract void uniform4iv(JSObject location, Int32Array v);

        @JSMethod
        public abstract void uniformMatrix2fv(JSObject location, boolean transpose, Float32Array value);

        @JSMethod
        public abstract void uniformMatrix3fv(JSObject location, boolean transpose, Float32Array value);

        @JSMethod
        public abstract void uniformMatrix4fv(JSObject location, boolean transpose, Float32Array value);

        @JSMethod
        public abstract void uniformMatrix2x3fv(JSObject location, boolean transpose, Float32Array value);

        @JSMethod
        public abstract void uniformMatrix2x4fv(JSObject location, boolean transpose, Float32Array value);

        @JSMethod
        public abstract void uniformMatrix3x2fv(JSObject location, boolean transpose, Float32Array value);

        @JSMethod
        public abstract void uniformMatrix3x4fv(JSObject location, boolean transpose, Float32Array value);

        @JSMethod
        public abstract void uniformMatrix4x2fv(JSObject location, boolean transpose, Float32Array value);

        @JSMethod
        public abstract void uniformMatrix4x3fv(JSObject location, boolean transpose, Float32Array value);

        @JSMethod
        public abstract void uniformBlockBinding(JSObject program, int uniformBlockIndex, int uniformBlockBinding);

        // ----- Buffer Objects -----
        @JSMethod
        public abstract JSObject createBuffer();

        @JSMethod
        public abstract void deleteBuffer(JSObject buffer);

        @JSMethod
        public abstract void bindBuffer(int target, JSObject buffer);

        @JSMethod
        public abstract void bufferData(int target, int size, int usage);

        @JSMethod
        public abstract void bufferData(int target, ArrayBuffer data, int usage);

        @JSMethod
        public abstract void bufferData(int target, Float32Array data, int usage);

        @JSMethod
        public abstract void bufferData(int target, Int32Array data, int usage);

        @JSMethod
        public abstract void bufferData(int target, Uint8Array data, int usage);

        @JSMethod
        public abstract void bufferSubData(int target, int offset, Float32Array data);

        @JSMethod
        public abstract void bufferSubData(int target, int offset, Int32Array data);

        @JSMethod
        public abstract void bufferSubData(int target, int offset, Uint8Array data);

        @JSMethod
        public abstract void bufferSubData(int target, int offset, ArrayBuffer data);

        @JSMethod
        public abstract JSObject getBufferParameter(int target, int pname);

        @JSMethod
        public abstract void copyBufferSubData(int readTarget, int writeTarget, int readOffset, int writeOffset, int size);

        @JSMethod
        public abstract void bindBufferRange(int target, int index, JSObject buffer, int offset, int size);

        @JSMethod
        public abstract void bindBufferBase(int target, int index, JSObject buffer);

        @JSMethod
        public abstract void clearBufferfv(int buffer, int drawbuffer, Float32Array value);

        @JSMethod
        public abstract void clearBufferiv(int buffer, int drawbuffer, Int32Array value);

        @JSMethod
        public abstract void clearBufferuiv(int buffer, int drawbuffer, Int32Array value);

        @JSMethod
        public abstract void clearBufferfi(int buffer, int drawbuffer, float depth, int stencil);

        // ----- Vertex Attributes -----
        @JSMethod
        public abstract void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int offset);

        @JSMethod
        public abstract void vertexAttribIPointer(int index, int size, int type, int stride, int offset);

        @JSMethod
        public abstract void vertexAttribDivisor(int index, int divisor);

        @JSMethod
        public abstract void enableVertexAttribArray(int index);

        @JSMethod
        public abstract void disableVertexAttribArray(int index);

        @JSMethod
        public abstract void vertexAttrib1f(int index, float x);

        @JSMethod
        public abstract void vertexAttrib2f(int index, float x, float y);

        @JSMethod
        public abstract void vertexAttrib3f(int index, float x, float y, float z);

        @JSMethod
        public abstract void vertexAttrib4f(int index, float x, float y, float z, float w);

        @JSMethod
        public abstract JSObject getVertexAttrib(int index, int pname);

        @JSMethod
        public abstract int getVertexAttribOffset(int index, int pname);

        // ----- Drawing -----
        @JSMethod
        public abstract void drawArrays(int mode, int first, int count);

        @JSMethod
        public abstract void drawElements(int mode, int count, int type, int offset);

        @JSMethod
        public abstract void drawRangeElements(int mode, int start, int end, int count, int type, int offset);

        @JSMethod
        public abstract void drawArraysInstanced(int mode, int first, int count, int instanceCount);

        @JSMethod
        public abstract void drawElementsInstanced(int mode, int count, int type, int offset, int instanceCount);

        // ----- Vertex Array Objects -----
        @JSMethod
        public abstract JSObject createVertexArray();

        @JSMethod
        public abstract void deleteVertexArray(JSObject vertexArray);

        @JSMethod
        public abstract void bindVertexArray(JSObject vertexArray);

        @JSMethod
        public abstract boolean isVertexArray(JSObject vertexArray);

        // ----- Texture Objects -----
        @JSMethod
        public abstract JSObject createTexture();

        @JSMethod
        public abstract void deleteTexture(JSObject texture);

        @JSMethod
        public abstract void bindTexture(int target, JSObject texture);

        @JSMethod
        public abstract void texImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, JSObject source);

        @JSMethod
        public abstract void texImage2D(int target, int level, int internalformat, int format, int type, JSObject source);

        @JSMethod
        public abstract void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, JSObject source);

        @JSMethod
        public abstract void texSubImage2D(int target, int level, int xoffset, int yoffset, int format, int type, JSObject source);

        @JSMethod
        public abstract void texParameterf(int target, int pname, float param);

        @JSMethod
        public abstract void texParameteri(int target, int pname, int param);

        @JSMethod
        public abstract JSObject getTexParameter(int target, int pname);

        @JSMethod
        public abstract void generateMipmap(int target);

        @JSMethod
        public abstract void activeTexture(int texture);

        @JSMethod
        public abstract void pixelStorei(int pname, int param);

        @JSMethod
        public abstract void readPixels(int x, int y, int width, int height, int format, int type, ArrayBuffer pixels);

        @JSMethod
        public abstract void readPixels(int x, int y, int width, int height, int format, int type, int offset);

        // ----- WebGL2 Texture Methods -----
        @JSMethod
        public abstract void texImage3D(int target, int level, int internalformat, int width, int height, int depth, int border, int format, int type, JSObject source);

        @JSMethod
        public abstract void texImage3D(int target, int level, int internalformat, int width, int height, int depth, int border, int format, int type, int offset);

        @JSMethod
        public abstract void texSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int type, JSObject source);

        @JSMethod
        public abstract void texSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int type, int offset);

        @JSMethod
        public abstract void texStorage2D(int target, int levels, int internalformat, int width, int height);

        @JSMethod
        public abstract void texStorage3D(int target, int levels, int internalformat, int width, int height, int depth);

        @JSMethod
        public abstract void copyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height);

        @JSMethod
        public abstract void copyTexSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int x, int y, int width, int height);

        @JSMethod
        public abstract void compressedTexImage2D(int target, int level, int internalformat, int width, int height, int border, ArrayBuffer data);

        @JSMethod
        public abstract void compressedTexImage2D(int target, int level, int internalformat, int width, int height, int border, int imageSize, int offset);

        @JSMethod
        public abstract void compressedTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, ArrayBuffer data);

        @JSMethod
        public abstract void compressedTexImage3D(int target, int level, int internalformat, int width, int height, int depth, int border, int imageSize, int offset);

        @JSMethod
        public abstract void compressedTexSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int imageSize, int offset);

        // ----- Sampler Objects -----
        @JSMethod
        public abstract JSObject createSampler();

        @JSMethod
        public abstract void deleteSampler(JSObject sampler);

        @JSMethod
        public abstract void bindSampler(int unit, JSObject sampler);

        @JSMethod
        public abstract void samplerParameteri(JSObject sampler, int pname, int param);

        @JSMethod
        public abstract void samplerParameterf(JSObject sampler, int pname, float param);

        @JSMethod
        public abstract JSObject getSamplerParameter(JSObject sampler, int pname);

        @JSMethod
        public abstract boolean isSampler(JSObject sampler);

        // ----- Query Objects -----
        @JSMethod
        public abstract JSObject createQuery();

        @JSMethod
        public abstract void deleteQuery(JSObject query);

        @JSMethod
        public abstract boolean isQuery(JSObject query);

        @JSMethod
        public abstract void beginQuery(int target, JSObject query);

        @JSMethod
        public abstract void endQuery(int target);

        @JSMethod
        public abstract JSObject getQuery(int target, int pname);

        @JSMethod
        public abstract JSObject getQueryParameter(JSObject query, int pname);

        @JSMethod
        public abstract void queryCounter(JSObject query, int target);

        // ----- Transform Feedback -----
        @JSMethod
        public abstract JSObject createTransformFeedback();

        @JSMethod
        public abstract void deleteTransformFeedback(JSObject transformFeedback);

        @JSMethod
        public abstract void bindTransformFeedback(int target, JSObject transformFeedback);

        @JSMethod
        public abstract void beginTransformFeedback(int primitiveMode);

        @JSMethod
        public abstract void endTransformFeedback();

        @JSMethod
        public abstract void transformFeedbackVaryings(JSObject program, JSObject varyings, int bufferMode);

        @JSMethod
        public abstract JSObject getTransformFeedbackVarying(JSObject program, int index);

        @JSMethod
        public abstract void pauseTransformFeedback();

        @JSMethod
        public abstract void resumeTransformFeedback();

        // ----- Framebuffer Objects -----
        @JSMethod
        public abstract JSObject createFramebuffer();

        @JSMethod
        public abstract void deleteFramebuffer(JSObject framebuffer);

        @JSMethod
        public abstract void bindFramebuffer(int target, JSObject framebuffer);

        @JSMethod
        public abstract int checkFramebufferStatus(int target);

        @JSMethod
        public abstract void framebufferTexture2D(int target, int attachment, int textarget, JSObject texture, int level);

        @JSMethod
        public abstract void framebufferTextureLayer(int target, int attachment, JSObject texture, int level, int layer);

        @JSMethod
        public abstract void framebufferRenderbuffer(int target, int attachment, int renderbuffertarget, JSObject renderbuffer);

        @JSMethod
        public abstract JSObject getFramebufferAttachmentParameter(int target, int attachment, int pname);

        @JSMethod
        public abstract void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter);

        @JSMethod
        public abstract void invalidateFramebuffer(int target, JSObject attachments);

        @JSMethod
        public abstract void invalidateSubFramebuffer(int target, JSObject attachments, int x, int y, int width, int height);

        @JSMethod
        public abstract void readBuffer(int src);

        @JSMethod
        public abstract void drawBuffers(JSObject buffers);

        // ----- Renderbuffer Objects -----
        @JSMethod
        public abstract JSObject createRenderbuffer();

        @JSMethod
        public abstract void deleteRenderbuffer(JSObject renderbuffer);

        @JSMethod
        public abstract void bindRenderbuffer(int target, JSObject renderbuffer);

        @JSMethod
        public abstract void renderbufferStorage(int target, int internalformat, int width, int height);

        @JSMethod
        public abstract void renderbufferStorageMultisample(int target, int samples, int internalformat, int width, int height);

        @JSMethod
        public abstract JSObject getRenderbufferParameter(int target, int pname);

        // ----- Sync Objects -----
        @JSMethod
        public abstract JSObject fenceSync(int condition, int flags);

        @JSMethod
        public abstract boolean isSync(JSObject sync);

        @JSMethod
        public abstract void deleteSync(JSObject sync);

        @JSMethod
        public abstract int clientWaitSync(JSObject sync, int flags, double timeout);

        @JSMethod
        public abstract void waitSync(JSObject sync, int flags, double timeout);

        @JSMethod
        public abstract JSObject getSyncParameter(JSObject sync, int pname);

        // ----- Get Extensions -----
        @JSMethod
        public abstract JSObject getExtension(String name);

        @JSMethod
        public abstract JSObject getSupportedExtensions();

        // ----- Flush / Finish -----
        @JSMethod
        public abstract void flush();

        @JSMethod
        public abstract void finish();
}
