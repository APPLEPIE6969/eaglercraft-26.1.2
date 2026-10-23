package net.lax1dude.eaglercraft.v2_6.internal;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.typedarrays.Int32Array;
import org.teavm.jso.typedarrays.Uint8Array;

import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.WebGL2RenderingContext;

/**
 * Platform OpenGL implementation for EaglerCraft 26.1.2 on the TeaVM/browser platform.
 * Provides WebGL2-only rendering with no WebGL1 fallback.
 *
 * <p>Key 26.1.2 features:</p>
 * <ul>
 *   <li>WebGL2 native - all GL calls go through WebGL2RenderingContext</li>
 *   <li>EXT_color_buffer_float for floating-point render targets</li>
 *   <li>OES_texture_float_linear for float texture filtering</li>
 *   <li>WEBGL_multi_draw for batched rendering</li>
 *   <li>Transform feedback for GPU particles</li>
 *   <li>Uniform buffer objects for efficient uniform updates</li>
 *   <li>Sampler objects for independent sampler state</li>
 *   <li>Query objects for occlusion and timer queries</li>
 *   <li>3D textures and 2D array textures</li>
 *   <li>Pixel pack/unpack buffer objects for async readback</li>
 *   <li>Vertex array objects (VAO) for efficient state switching</li>
 * </ul>
 */
public class PlatformOpenGL {

        /** The WebGL2 rendering context. */
        static WebGL2RenderingContext gl;

        /** Extension: EXT_color_buffer_float - enables float render targets. */
        static JSObject extColorBufferFloat;

        /** Extension: OES_texture_float_linear - enables float texture filtering. */
        static JSObject extTextureFloatLinear;

        /** Extension: WEBGL_multi_draw - enables multi-draw batching. */
        static JSObject extMultiDraw;

        /** Whether the context has been lost. */
        static boolean contextLost = false;

        /** Cached string values from GL queries. */
        private static String glVersion;
        private static String glVendor;
        private static String glRenderer;

        // ========== Constants (mirror GL constants for Java code) ==========
        public static final int GL_FALSE = 0;
        public static final int GL_TRUE = 1;
        public static final int GL_NO_ERROR = 0;
        public static final int GL_ZERO = 0;
        public static final int GL_ONE = 1;

        /**
         * Initializes the OpenGL platform. Called by ClientMain during startup.
         * Loads extensions and caches GL capability strings.
         */
        public static void _init() {
                gl = ClientMain.getWebGL2();
                if (gl == null) {
                        throw new RuntimeException("WebGL2 context is null during PlatformOpenGL init!");
                }

                // Load important extensions
                extColorBufferFloat = gl.getExtension("EXT_color_buffer_float");
                extTextureFloatLinear = gl.getExtension("OES_texture_float_linear");
                extMultiDraw = gl.getExtension("WEBGL_multi_draw");

                if (extColorBufferFloat == null) {
                        ClientMain.warn("EXT_color_buffer_float not available - float render targets may not work");
                }
                if (extTextureFloatLinear == null) {
                        ClientMain.warn("OES_texture_float_linear not available - float texture filtering may not work");
                }

                // Cache GL strings — WebGL2 uses getParameter() not getString()
                // (getString is OpenGL ES API, not WebGL)
                glVersion = getParameterString(WebGL2RenderingContext.VERSION);
                glVendor = getParameterString(WebGL2RenderingContext.VENDOR);
                glRenderer = getParameterString(WebGL2RenderingContext.RENDERER);

                ClientMain.log("[PlatformOpenGL] WebGL2 initialized: " + glVersion);
                ClientMain.log("[PlatformOpenGL] Vendor: " + glVendor + ", Renderer: " + glRenderer);
        }

        /**
         * Gets a string parameter from WebGL2 using getParameter() (not getString()
         * which doesn't exist in the WebGL API).
         */
        @JSBody(params = { "gl", "pname" }, script = "var r = gl.getParameter(pname); return r ? '' + r : '';")
        private static native String getParameterString(WebGL2RenderingContext gl, int pname);

        private static String getParameterString(int pname) {
                return getParameterString(gl, pname);
        }

        // ========== Context State ==========

        public static void _wglViewport(int x, int y, int w, int h) {
                gl.viewport(x, y, w, h);
        }

        public static void _wglScissor(int x, int y, int w, int h) {
                gl.scissor(x, y, w, h);
        }

        public static void _wglClearColor(float r, float g, float b, float a) {
                gl.clearColor(r, g, b, a);
        }

        public static void _wglClearDepth(double d) {
                gl.clearDepth(d);
        }

        public static void _wglClearStencil(int s) {
                gl.clearStencil(s);
        }

        public static void _wglClear(int mask) {
                gl.clear(mask);
        }

        public static void _wglEnable(int cap) {
                gl.enable(cap);
        }

        public static void _wglDisable(int cap) {
                gl.disable(cap);
        }

        public static boolean _wglIsEnabled(int cap) {
                return gl.isEnabled(cap);
        }

        public static int _wglGetError() {
                return gl.getError();
        }

        // ========== Blending ==========

        public static void _wglBlendFunc(int sfactor, int dfactor) {
                gl.blendFunc(sfactor, dfactor);
        }

        public static void _wglBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
                gl.blendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
        }

        public static void _wglBlendEquation(int mode) {
                gl.blendEquation(mode);
        }

        public static void _wglBlendEquationSeparate(int modeRGB, int modeAlpha) {
                gl.blendEquationSeparate(modeRGB, modeAlpha);
        }

        public static void _wglBlendColor(float r, float g, float b, float a) {
                gl.blendColor(r, g, b, a);
        }

        // ========== Depth / Stencil ==========

        public static void _wglDepthFunc(int func) {
                gl.depthFunc(func);
        }

        public static void _wglDepthMask(boolean mask) {
                gl.depthMask(mask);
        }

        public static void _wglDepthRange(double near, double far) {
                gl.depthRange(near, far);
        }

        public static void _wglStencilFunc(int func, int ref, int mask) {
                gl.stencilFunc(func, ref, mask);
        }

        public static void _wglStencilFuncSeparate(int face, int func, int ref, int mask) {
                gl.stencilFuncSeparate(face, func, ref, mask);
        }

        public static void _wglStencilOp(int sfail, int dpfail, int dppass) {
                gl.stencilOp(sfail, dpfail, dppass);
        }

        public static void _wglStencilOpSeparate(int face, int sfail, int dpfail, int dppass) {
                gl.stencilOpSeparate(face, sfail, dpfail, dppass);
        }

        public static void _wglStencilMask(int mask) {
                gl.stencilMask(mask);
        }

        public static void _wglStencilMaskSeparate(int face, int mask) {
                gl.stencilMaskSeparate(face, mask);
        }

        // ========== Color / Face / Polygon ==========

        public static void _wglColorMask(boolean r, boolean g, boolean b, boolean a) {
                gl.colorMask(r, g, b, a);
        }

        public static void _wglCullFace(int mode) {
                gl.cullFace(mode);
        }

        public static void _wglFrontFace(int mode) {
                gl.frontFace(mode);
        }

        public static void _wglPolygonOffset(float factor, float units) {
                gl.polygonOffset(factor, units);
        }

        public static void _wglLineWidth(float width) {
                gl.lineWidth(width);
        }

        public static void _wglSampleCoverage(float value, boolean invert) {
                gl.sampleCoverage(value, invert);
        }

        // ========== Shader Objects ==========

        public static JSObject _wglCreateShader(int type) {
                return gl.createShader(type);
        }

        public static void _wglShaderSource(JSObject shader, String source) {
                gl.shaderSource(shader, source);
        }

        public static void _wglCompileShader(JSObject shader) {
                gl.compileShader(shader);
        }

        public static void _wglDeleteShader(JSObject shader) {
                gl.deleteShader(shader);
        }

        public static boolean _wglGetShaderCompiled(JSObject shader) {
                return gl.getShaderParameter(shader, WebGL2RenderingContext.COMPILE_STATUS) == GL_TRUE;
        }

        public static String _wglGetShaderInfoLog(JSObject shader) {
                return gl.getShaderInfoLog(shader);
        }

        // ========== Program Objects ==========

        public static JSObject _wglCreateProgram() {
                return gl.createProgram();
        }

        public static void _wglAttachShader(JSObject program, JSObject shader) {
                gl.attachShader(program, shader);
        }

        public static void _wglDetachShader(JSObject program, JSObject shader) {
                gl.detachShader(program, shader);
        }

        public static void _wglLinkProgram(JSObject program) {
                gl.linkProgram(program);
        }

        public static void _wglUseProgram(JSObject program) {
                gl.useProgram(program);
        }

        public static void _wglDeleteProgram(JSObject program) {
                gl.deleteProgram(program);
        }

        public static boolean _wglGetProgramLinked(JSObject program) {
                return gl.getProgramParameter(program, WebGL2RenderingContext.LINK_STATUS) == GL_TRUE;
        }

        public static void _wglValidateProgram(JSObject program) {
                gl.validateProgram(program);
        }

        public static String _wglGetProgramInfoLog(JSObject program) {
                return gl.getProgramInfoLog(program);
        }

        public static int _wglGetAttribLocation(JSObject program, String name) {
                return gl.getAttribLocation(program, name);
        }

        public static JSObject _wglGetUniformLocation(JSObject program, String name) {
                return gl.getUniformLocation(program, name);
        }

        // ========== Uniforms ==========

        public static void _wglUniform1f(JSObject loc, float x) {
                gl.uniform1f(loc, x);
        }

        public static void _wglUniform2f(JSObject loc, float x, float y) {
                gl.uniform2f(loc, x, y);
        }

        public static void _wglUniform3f(JSObject loc, float x, float y, float z) {
                gl.uniform3f(loc, x, y, z);
        }

        public static void _wglUniform4f(JSObject loc, float x, float y, float z, float w) {
                gl.uniform4f(loc, x, y, z, w);
        }

        public static void _wglUniform1i(JSObject loc, int x) {
                gl.uniform1i(loc, x);
        }

        public static void _wglUniform2i(JSObject loc, int x, int y) {
                gl.uniform2i(loc, x, y);
        }

        public static void _wglUniform3i(JSObject loc, int x, int y, int z) {
                gl.uniform3i(loc, x, y, z);
        }

        public static void _wglUniform4i(JSObject loc, int x, int y, int z, int w) {
                gl.uniform4i(loc, x, y, z, w);
        }

        public static void _wglUniform1ui(JSObject loc, int x) {
                gl.uniform1ui(loc, x);
        }

        public static void _wglUniform2ui(JSObject loc, int x, int y) {
                gl.uniform2ui(loc, x, y);
        }

        public static void _wglUniform3ui(JSObject loc, int x, int y, int z) {
                gl.uniform3ui(loc, x, y, z);
        }

        public static void _wglUniform4ui(JSObject loc, int x, int y, int z, int w) {
                gl.uniform4ui(loc, x, y, z, w);
        }

        public static void _wglUniform1fv(JSObject loc, Float32Array v) {
                gl.uniform1fv(loc, v);
        }

        public static void _wglUniform2fv(JSObject loc, Float32Array v) {
                gl.uniform2fv(loc, v);
        }

        public static void _wglUniform3fv(JSObject loc, Float32Array v) {
                gl.uniform3fv(loc, v);
        }

        public static void _wglUniform4fv(JSObject loc, Float32Array v) {
                gl.uniform4fv(loc, v);
        }

        public static void _wglUniform1iv(JSObject loc, Int32Array v) {
                gl.uniform1iv(loc, v);
        }

        public static void _wglUniform2iv(JSObject loc, Int32Array v) {
                gl.uniform2iv(loc, v);
        }

        public static void _wglUniform3iv(JSObject loc, Int32Array v) {
                gl.uniform3iv(loc, v);
        }

        public static void _wglUniform4iv(JSObject loc, Int32Array v) {
                gl.uniform4iv(loc, v);
        }

        public static void _wglUniformMatrix2fv(JSObject loc, boolean transpose, Float32Array v) {
                gl.uniformMatrix2fv(loc, transpose, v);
        }

        public static void _wglUniformMatrix3fv(JSObject loc, boolean transpose, Float32Array v) {
                gl.uniformMatrix3fv(loc, transpose, v);
        }

        public static void _wglUniformMatrix4fv(JSObject loc, boolean transpose, Float32Array v) {
                gl.uniformMatrix4fv(loc, transpose, v);
        }

        public static void _wglUniformMatrix2x3fv(JSObject loc, boolean transpose, Float32Array v) {
                gl.uniformMatrix2x3fv(loc, transpose, v);
        }

        public static void _wglUniformMatrix2x4fv(JSObject loc, boolean transpose, Float32Array v) {
                gl.uniformMatrix2x4fv(loc, transpose, v);
        }

        public static void _wglUniformMatrix3x2fv(JSObject loc, boolean transpose, Float32Array v) {
                gl.uniformMatrix3x2fv(loc, transpose, v);
        }

        public static void _wglUniformMatrix3x4fv(JSObject loc, boolean transpose, Float32Array v) {
                gl.uniformMatrix3x4fv(loc, transpose, v);
        }

        public static void _wglUniformMatrix4x2fv(JSObject loc, boolean transpose, Float32Array v) {
                gl.uniformMatrix4x2fv(loc, transpose, v);
        }

        public static void _wglUniformMatrix4x3fv(JSObject loc, boolean transpose, Float32Array v) {
                gl.uniformMatrix4x3fv(loc, transpose, v);
        }

        public static void _wglUniformBlockBinding(JSObject program, int uniformBlockIndex, int uniformBlockBinding) {
                gl.uniformBlockBinding(program, uniformBlockIndex, uniformBlockBinding);
        }

        public static int _wglGetUniformBlockIndex(JSObject program, String uniformBlockName) {
                return gl.getUniformBlockIndex(program, uniformBlockName);
        }

        // ========== Buffer Objects ==========

        public static JSObject _wglCreateBuffer() {
                return gl.createBuffer();
        }

        public static void _wglDeleteBuffer(JSObject buffer) {
                gl.deleteBuffer(buffer);
        }

        public static void _wglBindBuffer(int target, JSObject buffer) {
                gl.bindBuffer(target, buffer);
        }

        public static void _wglBufferData(int target, int size, int usage) {
                gl.bufferData(target, size, usage);
        }

        public static void _wglBufferData(int target, ArrayBuffer data, int usage) {
                gl.bufferData(target, data, usage);
        }

        public static void _wglBufferData(int target, Float32Array data, int usage) {
                gl.bufferData(target, data, usage);
        }

        public static void _wglBufferData(int target, Int32Array data, int usage) {
                gl.bufferData(target, data, usage);
        }

        public static void _wglBufferData(int target, Uint8Array data, int usage) {
                gl.bufferData(target, data, usage);
        }

        public static void _wglBufferSubData(int target, int offset, Float32Array data) {
                gl.bufferSubData(target, offset, data);
        }

        public static void _wglBufferSubData(int target, int offset, Int32Array data) {
                gl.bufferSubData(target, offset, data);
        }

        public static void _wglBufferSubData(int target, int offset, Uint8Array data) {
                gl.bufferSubData(target, offset, data);
        }

        public static void _wglBufferSubData(int target, int offset, ArrayBuffer data) {
                gl.bufferSubData(target, offset, data);
        }

        public static void _wglCopyBufferSubData(int readTarget, int writeTarget, int readOffset, int writeOffset, int size) {
                gl.copyBufferSubData(readTarget, writeTarget, readOffset, writeOffset, size);
        }

        public static void _wglBindBufferRange(int target, int index, JSObject buffer, int offset, int size) {
                gl.bindBufferRange(target, index, buffer, offset, size);
        }

        public static void _wglBindBufferBase(int target, int index, JSObject buffer) {
                gl.bindBufferBase(target, index, buffer);
        }

        public static void _wglClearBufferfv(int buffer, int drawbuffer, Float32Array value) {
                gl.clearBufferfv(buffer, drawbuffer, value);
        }

        public static void _wglClearBufferiv(int buffer, int drawbuffer, Int32Array value) {
                gl.clearBufferiv(buffer, drawbuffer, value);
        }

        public static void _wglClearBufferfi(int buffer, int drawbuffer, float depth, int stencil) {
                gl.clearBufferfi(buffer, drawbuffer, depth, stencil);
        }

        // ========== Vertex Attributes ==========

        public static void _wglVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int offset) {
                gl.vertexAttribPointer(index, size, type, normalized, stride, offset);
        }

        public static void _wglVertexAttribIPointer(int index, int size, int type, int stride, int offset) {
                gl.vertexAttribIPointer(index, size, type, stride, offset);
        }

        public static void _wglVertexAttribDivisor(int index, int divisor) {
                gl.vertexAttribDivisor(index, divisor);
        }

        public static void _wglEnableVertexAttribArray(int index) {
                gl.enableVertexAttribArray(index);
        }

        public static void _wglDisableVertexAttribArray(int index) {
                gl.disableVertexAttribArray(index);
        }

        public static void _wglVertexAttrib1f(int index, float x) {
                gl.vertexAttrib1f(index, x);
        }

        public static void _wglVertexAttrib2f(int index, float x, float y) {
                gl.vertexAttrib2f(index, x, y);
        }

        public static void _wglVertexAttrib3f(int index, float x, float y, float z) {
                gl.vertexAttrib3f(index, x, y, z);
        }

        public static void _wglVertexAttrib4f(int index, float x, float y, float z, float w) {
                gl.vertexAttrib4f(index, x, y, z, w);
        }

        // ========== Drawing ==========

        public static void _wglDrawArrays(int mode, int first, int count) {
                gl.drawArrays(mode, first, count);
        }

        public static void _wglDrawElements(int mode, int count, int type, int offset) {
                gl.drawElements(mode, count, type, offset);
        }

        public static void _wglDrawRangeElements(int mode, int start, int end, int count, int type, int offset) {
                gl.drawRangeElements(mode, start, end, count, type, offset);
        }

        public static void _wglDrawArraysInstanced(int mode, int first, int count, int instanceCount) {
                gl.drawArraysInstanced(mode, first, count, instanceCount);
        }

        public static void _wglDrawElementsInstanced(int mode, int count, int type, int offset, int instanceCount) {
                gl.drawElementsInstanced(mode, count, type, offset, instanceCount);
        }

        /**
         * Multi-draw: draws multiple sets of primitives in one call.
         * Uses WEBGL_multi_draw extension if available.
         */
        public static void _wglMultiDrawArrays(int mode, Int32Array firsts, Int32Array counts, int drawcount) {
                if (extMultiDraw != null) {
                        multiDrawArraysEXT(mode, firsts, 0, counts, 0, drawcount);
                } else {
                        // Fallback: loop through draws
                        for (int i = 0; i < drawcount; i++) {
                                gl.drawArrays(mode, firsts.get(i), counts.get(i));
                        }
                }
        }

        /**
         * Multi-draw elements: draws multiple indexed primitive sets in one call.
         */
        public static void _wglMultiDrawElements(int mode, Int32Array counts, int type, Int32Array offsets, int drawcount) {
                if (extMultiDraw != null) {
                        multiDrawElementsEXT(mode, counts, 0, type, offsets, 0, drawcount);
                } else {
                        for (int i = 0; i < drawcount; i++) {
                                gl.drawElements(mode, counts.get(i), type, offsets.get(i));
                        }
                }
        }

        // ========== Vertex Array Objects ==========

        public static JSObject _wglCreateVertexArray() {
                return gl.createVertexArray();
        }

        public static void _wglDeleteVertexArray(JSObject vao) {
                gl.deleteVertexArray(vao);
        }

        public static void _wglBindVertexArray(JSObject vao) {
                gl.bindVertexArray(vao);
        }

        // ========== Texture Objects ==========

        public static JSObject _wglCreateTexture() {
                return gl.createTexture();
        }

        public static void _wglDeleteTexture(JSObject texture) {
                gl.deleteTexture(texture);
        }

        public static void _wglBindTexture(int target, JSObject texture) {
                gl.bindTexture(target, texture);
        }

        public static void _wglTexImage2D(int target, int level, int internalformat, int width, int height,
                                                                           int border, int format, int type, JSObject source) {
                gl.texImage2D(target, level, internalformat, width, height, border, format, type, source);
        }

        public static void _wglTexImage2D(int target, int level, int internalformat, int format, int type, JSObject source) {
                gl.texImage2D(target, level, internalformat, format, type, source);
        }

        public static void _wglTexSubImage2D(int target, int level, int xoffset, int yoffset,
                                                                                  int width, int height, int format, int type, JSObject source) {
                gl.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, source);
        }

        public static void _wglTexSubImage2D(int target, int level, int xoffset, int yoffset,
                                                                                  int format, int type, JSObject source) {
                gl.texSubImage2D(target, level, xoffset, yoffset, format, type, source);
        }

        public static void _wglTexParameteri(int target, int pname, int param) {
                gl.texParameteri(target, pname, param);
        }

        public static void _wglTexParameterf(int target, int pname, float param) {
                gl.texParameterf(target, pname, param);
        }

        public static void _wglGenerateMipmap(int target) {
                gl.generateMipmap(target);
        }

        public static void _wglActiveTexture(int texture) {
                gl.activeTexture(texture);
        }

        public static void _wglPixelStorei(int pname, int param) {
                gl.pixelStorei(pname, param);
        }

        // ========== WebGL2 Texture Methods ==========

        public static void _wglTexImage3D(int target, int level, int internalformat, int width, int height, int depth,
                                                                           int border, int format, int type, JSObject source) {
                gl.texImage3D(target, level, internalformat, width, height, depth, border, format, type, source);
        }

        public static void _wglTexImage3D(int target, int level, int internalformat, int width, int height, int depth,
                                                                           int border, int format, int type, int offset) {
                gl.texImage3D(target, level, internalformat, width, height, depth, border, format, type, offset);
        }

        public static void _wglTexSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset,
                                                                                  int width, int height, int depth, int format, int type, JSObject source) {
                gl.texSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, type, source);
        }

        public static void _wglTexSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset,
                                                                                  int width, int height, int depth, int format, int type, int offset) {
                gl.texSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, type, offset);
        }

        public static void _wglTexStorage2D(int target, int levels, int internalformat, int width, int height) {
                gl.texStorage2D(target, levels, internalformat, width, height);
        }

        public static void _wglTexStorage3D(int target, int levels, int internalformat, int width, int height, int depth) {
                gl.texStorage3D(target, levels, internalformat, width, height, depth);
        }

        public static void _wglCopyTexSubImage2D(int target, int level, int xoffset, int yoffset,
                                                                                  int x, int y, int width, int height) {
                gl.copyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
        }

        public static void _wglCopyTexSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset,
                                                                                          int x, int y, int width, int height) {
                gl.copyTexSubImage3D(target, level, xoffset, yoffset, zoffset, x, y, width, height);
        }

        public static void _wglCompressedTexImage2D(int target, int level, int internalformat,
                                                                                                 int width, int height, int border, ArrayBuffer data) {
                gl.compressedTexImage2D(target, level, internalformat, width, height, border, data);
        }

        public static void _wglCompressedTexImage2D(int target, int level, int internalformat,
                                                                                                 int width, int height, int border, int imageSize, int offset) {
                gl.compressedTexImage2D(target, level, internalformat, width, height, border, imageSize, offset);
        }

        public static void _wglCompressedTexSubImage2D(int target, int level, int xoffset, int yoffset,
                                                                                                         int width, int height, int format, ArrayBuffer data) {
                gl.compressedTexSubImage2D(target, level, xoffset, yoffset, width, height, format, data);
        }

        public static void _wglCompressedTexImage3D(int target, int level, int internalformat,
                                                                                                 int width, int height, int depth, int border,
                                                                                                 int imageSize, int offset) {
                gl.compressedTexImage3D(target, level, internalformat, width, height, depth, border, imageSize, offset);
        }

        public static void _wglCompressedTexSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset,
                                                                                                         int width, int height, int depth, int format,
                                                                                                         int imageSize, int offset) {
                gl.compressedTexSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, imageSize, offset);
        }

        // ========== Sampler Objects ==========

        public static JSObject _wglCreateSampler() {
                return gl.createSampler();
        }

        public static void _wglDeleteSampler(JSObject sampler) {
                gl.deleteSampler(sampler);
        }

        public static void _wglBindSampler(int unit, JSObject sampler) {
                gl.bindSampler(unit, sampler);
        }

        public static void _wglSamplerParameteri(JSObject sampler, int pname, int param) {
                gl.samplerParameteri(sampler, pname, param);
        }

        public static void _wglSamplerParameterf(JSObject sampler, int pname, float param) {
                gl.samplerParameterf(sampler, pname, param);
        }

        // ========== Query Objects ==========

        public static JSObject _wglCreateQuery() {
                return gl.createQuery();
        }

        public static void _wglDeleteQuery(JSObject query) {
                gl.deleteQuery(query);
        }

        public static void _wglBeginQuery(int target, JSObject query) {
                gl.beginQuery(target, query);
        }

        public static void _wglEndQuery(int target) {
                gl.endQuery(target);
        }

        public static int _wglGetQueryObjectui(JSObject query, int pname) {
                JSObject result = gl.getQueryParameter(query, pname);
                return queryResultToInt(result);
        }

        public static boolean _wglGetQueryObjectAvailable(JSObject query) {
                JSObject result = gl.getQueryParameter(query, WebGL2RenderingContext.QUERY_RESULT_AVAILABLE);
                return queryResultToBoolean(result);
        }

        // ========== Transform Feedback ==========

        public static JSObject _wglCreateTransformFeedback() {
                return gl.createTransformFeedback();
        }

        public static void _wglDeleteTransformFeedback(JSObject tf) {
                gl.deleteTransformFeedback(tf);
        }

        public static void _wglBindTransformFeedback(int target, JSObject tf) {
                gl.bindTransformFeedback(target, tf);
        }

        public static void _wglBeginTransformFeedback(int primitiveMode) {
                gl.beginTransformFeedback(primitiveMode);
        }

        public static void _wglEndTransformFeedback() {
                gl.endTransformFeedback();
        }

        public static void _wglPauseTransformFeedback() {
                gl.pauseTransformFeedback();
        }

        public static void _wglResumeTransformFeedback() {
                gl.resumeTransformFeedback();
        }

        public static void _wglTransformFeedbackVaryings(JSObject program, JSObject varyings, int bufferMode) {
                gl.transformFeedbackVaryings(program, varyings, bufferMode);
        }

        // ========== Framebuffer Objects ==========

        public static JSObject _wglCreateFramebuffer() {
                return gl.createFramebuffer();
        }

        public static void _wglDeleteFramebuffer(JSObject fb) {
                gl.deleteFramebuffer(fb);
        }

        public static void _wglBindFramebuffer(int target, JSObject fb) {
                gl.bindFramebuffer(target, fb);
        }

        public static int _wglCheckFramebufferStatus(int target) {
                return gl.checkFramebufferStatus(target);
        }

        public static void _wglFramebufferTexture2D(int target, int attachment, int textarget, JSObject texture, int level) {
                gl.framebufferTexture2D(target, attachment, textarget, texture, level);
        }

        public static void _wglFramebufferTextureLayer(int target, int attachment, JSObject texture, int level, int layer) {
                gl.framebufferTextureLayer(target, attachment, texture, level, layer);
        }

        public static void _wglFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, JSObject renderbuffer) {
                gl.framebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer);
        }

        public static void _wglBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1,
                                                                                        int dstX0, int dstY0, int dstX1, int dstY1,
                                                                                        int mask, int filter) {
                gl.blitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
        }

        public static void _wglInvalidateFramebuffer(int target, JSObject attachments) {
                gl.invalidateFramebuffer(target, attachments);
        }

        public static void _wglReadBuffer(int src) {
                gl.readBuffer(src);
        }

        public static void _wglDrawBuffers(JSObject buffers) {
                gl.drawBuffers(buffers);
        }

        // ========== Renderbuffer Objects ==========

        public static JSObject _wglCreateRenderbuffer() {
                return gl.createRenderbuffer();
        }

        public static void _wglDeleteRenderbuffer(JSObject rb) {
                gl.deleteRenderbuffer(rb);
        }

        public static void _wglBindRenderbuffer(int target, JSObject rb) {
                gl.bindRenderbuffer(target, rb);
        }

        public static void _wglRenderbufferStorage(int target, int internalformat, int width, int height) {
                gl.renderbufferStorage(target, internalformat, width, height);
        }

        public static void _wglRenderbufferStorageMultisample(int target, int samples, int internalformat, int width, int height) {
                gl.renderbufferStorageMultisample(target, samples, internalformat, width, height);
        }

        // ========== Sync Objects ==========

        public static JSObject _wglFenceSync(int condition, int flags) {
                return gl.fenceSync(condition, flags);
        }

        public static void _wglDeleteSync(JSObject sync) {
                gl.deleteSync(sync);
        }

        public static int _wglClientWaitSync(JSObject sync, int flags, long timeout) {
                return gl.clientWaitSync(sync, flags, (double) timeout);
        }

        public static void _wglWaitSync(JSObject sync, int flags, long timeout) {
                gl.waitSync(sync, flags, (double) timeout);
        }

        // ========== Read Pixels ==========

        public static void _wglReadPixels(int x, int y, int width, int height, int format, int type, ArrayBuffer pixels) {
                gl.readPixels(x, y, width, height, format, type, pixels);
        }

        public static void _wglReadPixels(int x, int y, int width, int height, int format, int type, int offset) {
                gl.readPixels(x, y, width, height, format, type, offset);
        }

        // ========== Flush / Finish ==========

        public static void _wglFlush() {
                gl.flush();
        }

        public static void _wglFinish() {
                gl.finish();
        }

        // ========== Get Parameter ==========

        public static int _wglGetInteger(int pname) {
                JSObject result = gl.getParameter(pname);
                return jsObjectToInt(result);
        }

        public static String _wglGetString(int pname) {
                return getParameterString(gl, pname);
        }

        public static JSObject _wglGetParameter(int pname) {
                return gl.getParameter(pname);
        }

        // ========== Extension Queries ==========

        public static JSObject _wglGetExtension(String name) {
                return gl.getExtension(name);
        }

        // ========== GL String Accessors ==========

        /**
         * Gets the cached GL version string.
         */
        public static String getGLVersion() {
                return glVersion;
        }

        /**
         * Gets the cached GL vendor string.
         */
        public static String getGLVendor() {
                return glVendor;
        }

        /**
         * Gets the cached GL renderer string.
         */
        public static String getGLRenderer() {
                return glRenderer;
        }

        /**
         * Checks if the context has been lost.
         */
        public static boolean isContextLost() {
                return contextLost;
        }

        // ========== GL11 Shim Support ==========

        /**
         * Alias for _wglCreateTexture() - matches LWJGL glGenTextures naming.
         */
        public static JSObject _wglGenTextures() {
                return _wglCreateTexture();
        }

        /**
         * Alias for _wglDeleteTexture() - matches LWJGL glDeleteTextures naming.
         */
        public static void _wglDeleteTextures(JSObject texture) {
                _wglDeleteTexture(texture);
        }

        /**
         * Reads integer GL state into an Int32Array.
         * Handles both single-value and multi-value parameters.
         */
        @JSBody(params = { "pname", "params" }, script = "" +
                        "var result = gl.getParameter(pname);" +
                        "if (typeof result === 'number') {" +
                        "  params[0] = result;" +
                        "} else if (result && result.length) {" +
                        "  for (var i = 0; i < result.length && i < params.length; i++) {" +
                        "    params[i] = result[i];" +
                        "  }" +
                        "}")
        public static native void _wglGetIntegerv(int pname, Int32Array params);

        /**
         * Reads float GL state into a Float32Array.
         * Handles both single-value and multi-value parameters.
         */
        @JSBody(params = { "pname", "params" }, script = "" +
                        "var result = gl.getParameter(pname);" +
                        "if (typeof result === 'number') {" +
                        "  params[0] = result;" +
                        "} else if (result && result.length) {" +
                        "  for (var i = 0; i < result.length && i < params.length; i++) {" +
                        "    params[i] = result[i];" +
                        "  }" +
                        "}")
        public static native void _wglGetFloatv(int pname, Float32Array params);

        /**
         * Reads boolean GL state into a Uint8Array.
         */
        @JSBody(params = { "pname", "params" }, script = "" +
                        "var result = gl.getParameter(pname);" +
                        "if (typeof result === 'boolean') {" +
                        "  params[0] = result ? 1 : 0;" +
                        "} else if (typeof result === 'number') {" +
                        "  params[0] = result !== 0 ? 1 : 0;" +
                        "} else if (result && result.length) {" +
                        "  for (var i = 0; i < result.length && i < params.length; i++) {" +
                        "    params[i] = result[i] !== 0 ? 1 : 0;" +
                        "  }" +
                        "}")
        public static native void _wglGetBooleanv(int pname, Uint8Array params);

        /**
         * Read pixels into a JSObject (supports typed array views like Uint8Array, Float32Array, etc.).
         */
        @JSBody(params = { "x", "y", "w", "h", "format", "type", "pixels" },
                        script = "gl.readPixels(x, y, w, h, format, type, pixels);")
        public static native void _wglReadPixels(int x, int y, int w, int h, int format, int type, JSObject pixels);

        // ========== Native Helper Methods ==========

        /**
         * Converts a JS query result to an int.
         */
        @JSBody(params = { "result" }, script = "return result | 0;")
        private static native int queryResultToInt(JSObject result);

        /**
         * Converts a JS query result to a boolean.
         */
        @JSBody(params = { "result" }, script = "return !!result;")
        private static native boolean queryResultToBoolean(JSObject result);

        /**
         * Converts a JS object to an int.
         */
        @JSBody(params = { "obj" }, script = "return obj | 0;")
        private static native int jsObjectToInt(JSObject obj);

        /**
         * WEBGL_multi_draw extension: multiDrawArraysWEB.
         */
        @JSBody(params = { "mode", "firstsList", "firstsOffset", "countsList", "countsOffset", "drawcount" },
                        script = "gl.getExtension('WEBGL_multi_draw').multiDrawArraysWEB(mode, firstsList, firstsOffset, countsList, countsOffset, drawcount);")
        private static native void multiDrawArraysEXT(int mode, Int32Array firstsList, int firstsOffset,
                                                                                                   Int32Array countsList, int countsOffset, int drawcount);

        /**
         * WEBGL_multi_draw extension: multiDrawElementsWEB.
         */
        @JSBody(params = { "mode", "countsList", "countsOffset", "type", "offsetsList", "offsetsOffset", "drawcount" },
                        script = "gl.getExtension('WEBGL_multi_draw').multiDrawElementsWEB(mode, countsList, countsOffset, type, offsetsList, offsetsOffset, drawcount);")
        private static native void multiDrawElementsEXT(int mode, Int32Array countsList, int countsOffset,
                                                                                                         int type, Int32Array offsetsList, int offsetsOffset, int drawcount);
}
