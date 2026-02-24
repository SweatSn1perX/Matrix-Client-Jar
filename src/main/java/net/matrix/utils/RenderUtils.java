package net.matrix.utils;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.DynamicUniformStorage;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.apache.commons.io.IOUtils;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import org.lwjgl.opengl.GL11;

import static org.lwjgl.system.MemoryUtil.*;

/**
 * Central 3D rendering utility for Matrix (MC 1.21.11).
 * <p>
 * Uses a batched vertex pipeline with proper RenderPipeline + RenderPass
 * and explicit framebuffer attachment binding.  All draw calls during a
 * frame accumulate into line and triangle buffers; a single
 * {@link #render(MatrixStack)} at end of frame flushes to the GPU.
 * <p>
 * Modules should pass <b>camera-relative</b> coordinates (world pos minus
 * cameraPos), matching the existing API contract.
 */
public class RenderUtils {

    // ─── Render Pipelines ────────────────────────────────────

    private static final RenderPipeline.Snippet MESH_SNIPPET = RenderPipeline.builder()
            .withUniform("MeshData", UniformType.UNIFORM_BUFFER)
            .buildSnippet();

    /** Lines — no depth test, translucent blend, no depth write, no cull. */
    static final RenderPipeline LINES_PIPELINE = RenderPipeline.builder(MESH_SNIPPET)
            .withLocation(Identifier.of("matrix", "pipeline/world_lines"))
            .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.DEBUG_LINES)
            .withVertexShader(Identifier.of("matrix", "shaders/pos_color.vert"))
            .withFragmentShader(Identifier.of("matrix", "shaders/pos_color.frag"))
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withCull(false)
            .build();

    /** Triangles — no depth test, translucent blend, no depth write, no cull. */
    static final RenderPipeline TRIANGLES_PIPELINE = RenderPipeline.builder(MESH_SNIPPET)
            .withLocation(Identifier.of("matrix", "pipeline/world_tris"))
            .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLES)
            .withVertexShader(Identifier.of("matrix", "shaders/pos_color.vert"))
            .withFragmentShader(Identifier.of("matrix", "shaders/pos_color.frag"))
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withCull(false)
            .build();

    private static final List<RenderPipeline> PIPELINES = List.of(LINES_PIPELINE, TRIANGLES_PIPELINE);

    /**
     * Precompile all custom render pipelines.
     * Called from ShaderLoaderMixin when shaders are loaded/reloaded.
     */
    public static void precompilePipelines() {
        var device = RenderSystem.getDevice();
        ResourceManager resources = MinecraftClient.getInstance().getResourceManager();

        for (RenderPipeline pipeline : PIPELINES) {
            device.precompilePipeline(pipeline, (identifier, shaderType) -> {
                var resource = resources.getResource(identifier).get();
                try (var in = resource.getInputStream()) {
                    return IOUtils.toString(in, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to load Matrix shader: " + identifier, e);
                }
            });
        }
    }

    // ─── Uniform buffer (proj + modelView) ──────────────────

    private static final int UBO_SIZE = new Std140SizeCalculator()
            .putMat4f()
            .putMat4f()
            .get();

    private static final DynamicUniformStorage<UBOData> UBO_STORAGE =
            new DynamicUniformStorage<>("Matrix - Mesh UBO", UBO_SIZE, 16);

    private static final UBOData UBO_DATA = new UBOData();

    private static final class UBOData implements DynamicUniformStorage.Uploadable {
        Matrix4f proj;
        Matrix4f modelView;

        @Override
        public void write(ByteBuffer buffer) {
            Std140Builder.intoBuffer(buffer)
                    .putMat4f(proj)
                    .putMat4f(modelView);
        }

        @Override
        public boolean equals(Object o) {
            return false; // never deduplicate
        }
    }

    // ─── Projection matrix cache ────────────────────────────

    /** Set once per frame by the mixin hook. */
    public static Matrix4f projection = new Matrix4f();

    // ─── Vertex + Index Buffers ─────────────────────────────

    // POSITION_COLOR vertex = 3 floats pos (12) + 1 int packed color (4) = 16 bytes
    private static final int VERTEX_SIZE = 16;

    // Lines
    private static ByteBuffer lineVerts;
    private static long lineVertsBase;
    private static int lineVertI;
    private static ByteBuffer lineIdxs;
    private static long lineIdxsBase;
    private static int lineIdxCount;

    // Triangles
    private static ByteBuffer triVerts;
    private static long triVertsBase;
    private static int triVertI;
    private static ByteBuffer triIdxs;
    private static long triIdxsBase;
    private static int triIdxCount;

    private static boolean building;
    private static float currentLineWidth = 1.0f;

    // ─── Frame lifecycle ────────────────────────────────────

    /**
     * Set the line width in pixels for subsequent line draws.
     * Call during render callbacks (between begin and render).
     */
    public static void setLineWidth(float width) {
        currentLineWidth = Math.max(0.5f, width);
    }

    /**
     * Call once, before dispatching any modules' 3D render callbacks.
     */
    public static void begin() {
        if (building) return;
        building = true;

        lineVertI = 0;
        lineIdxCount = 0;
        triVertI = 0;
        triIdxCount = 0;
        currentLineWidth = 1.0f;

        allocateIfNeeded();

        lineVertsBase = memAddress0(lineVerts);
        lineIdxsBase  = memAddress0(lineIdxs);
        triVertsBase  = memAddress0(triVerts);
        triIdxsBase   = memAddress0(triIdxs);
    }

    /**
     * Call once, after all modules have issued their draw calls.
     * Flushes batched geometry through the GPU pipeline.
     */
    public static void render(MatrixStack matrices) {
        if (!building) return;
        building = false;

        MinecraftClient mc = MinecraftClient.getInstance();
        Framebuffer fb = mc.getFramebuffer();
        if (fb == null) return;

        // The mixin already pushed the world position matrix onto
        // RenderSystem.getModelViewStack(), so we just read it directly.
        UBO_DATA.proj = projection;
        UBO_DATA.modelView = RenderSystem.getModelViewStack();

        if (lineIdxCount > 0) {
            GL11.glLineWidth(currentLineWidth);
            flushPipeline(fb, LINES_PIPELINE, lineVerts, lineVertI, lineIdxs, lineIdxCount);
            GL11.glLineWidth(1.0f); // restore default
        }
        if (triIdxCount > 0) {
            flushPipeline(fb, TRIANGLES_PIPELINE, triVerts, triVertI, triIdxs, triIdxCount);
        }

        UBO_STORAGE.clear();
    }

    private static void flushPipeline(Framebuffer fb, RenderPipeline pipeline,
                                      ByteBuffer verts, int vertCount,
                                      ByteBuffer idxs, int idxCount) {

        verts.limit(vertCount * VERTEX_SIZE).position(0);
        GpuBuffer vbo = VertexFormats.POSITION_COLOR.uploadImmediateVertexBuffer(verts);

        idxs.limit(idxCount * Integer.BYTES).position(0);
        GpuBuffer ibo = VertexFormats.POSITION_COLOR.uploadImmediateIndexBuffer(idxs);

        GpuBufferSlice meshData = UBO_STORAGE.write(UBO_DATA);

        RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(
                        () -> "Matrix RenderUtils",
                        fb.getColorAttachmentView(),
                        OptionalInt.empty(),
                        fb.getDepthAttachmentView(),
                        OptionalDouble.empty());

        pass.setPipeline(pipeline);
        pass.setUniform("MeshData", meshData);
        pass.setVertexBuffer(0, vbo);
        pass.setIndexBuffer(ibo, VertexFormat.IndexType.INT);
        pass.drawIndexed(0, 0, idxCount, 1);
        pass.close();
    }

    // ─── Buffer allocation / growth ─────────────────────────

    private static void allocateIfNeeded() {
        if (lineVerts == null) {
            lineVerts = BufferUtils.createByteBuffer(VERTEX_SIZE * 4096);
            lineIdxs  = BufferUtils.createByteBuffer(Integer.BYTES * 8192);
        }
        if (triVerts == null) {
            triVerts = BufferUtils.createByteBuffer(VERTEX_SIZE * 4096);
            triIdxs  = BufferUtils.createByteBuffer(Integer.BYTES * 8192);
        }
    }

    private static void ensureLineCapacity(int addVerts, int addIdxs) {
        int neededV = (lineVertI + addVerts) * VERTEX_SIZE;
        if (neededV >= lineVerts.capacity()) {
            int newSize = Math.max(lineVerts.capacity() * 2, neededV + VERTEX_SIZE * 256);
            ByteBuffer nb = BufferUtils.createByteBuffer(newSize);
            memCopy(memAddress0(lineVerts), memAddress0(nb), (long) lineVertI * VERTEX_SIZE);
            lineVerts = nb;
            lineVertsBase = memAddress0(nb);
        }
        int neededI = (lineIdxCount + addIdxs) * Integer.BYTES;
        if (neededI >= lineIdxs.capacity()) {
            int newSize = Math.max(lineIdxs.capacity() * 2, neededI + Integer.BYTES * 512);
            ByteBuffer nb = BufferUtils.createByteBuffer(newSize);
            memCopy(memAddress0(lineIdxs), memAddress0(nb), (long) lineIdxCount * Integer.BYTES);
            lineIdxs = nb;
            lineIdxsBase = memAddress0(nb);
        }
    }

    private static void ensureTriCapacity(int addVerts, int addIdxs) {
        int neededV = (triVertI + addVerts) * VERTEX_SIZE;
        if (neededV >= triVerts.capacity()) {
            int newSize = Math.max(triVerts.capacity() * 2, neededV + VERTEX_SIZE * 256);
            ByteBuffer nb = BufferUtils.createByteBuffer(newSize);
            memCopy(memAddress0(triVerts), memAddress0(nb), (long) triVertI * VERTEX_SIZE);
            triVerts = nb;
            triVertsBase = memAddress0(nb);
        }
        int neededI = (triIdxCount + addIdxs) * Integer.BYTES;
        if (neededI >= triIdxs.capacity()) {
            int newSize = Math.max(triIdxs.capacity() * 2, neededI + Integer.BYTES * 512);
            ByteBuffer nb = BufferUtils.createByteBuffer(newSize);
            memCopy(memAddress0(triIdxs), memAddress0(nb), (long) triIdxCount * Integer.BYTES);
            triIdxs = nb;
            triIdxsBase = memAddress0(nb);
        }
    }

    // ─── Low-level vertex writes ────────────────────────────

    /** Append a line vertex (POSITION_COLOR) and return its index. */
    private static int putLineVert(float x, float y, float z, int r, int g, int b, int a) {
        long p = lineVertsBase + (long) lineVertI * VERTEX_SIZE;
        memPutFloat(p,      x);
        memPutFloat(p + 4,  y);
        memPutFloat(p + 8,  z);
        memPutInt(p + 12, ((a & 0xFF) << 24) | ((b & 0xFF) << 16) | ((g & 0xFF) << 8) | (r & 0xFF));
        return lineVertI++;
    }

    /** Append two line indices. */
    private static void putLineIdx(int i1, int i2) {
        long p = lineIdxsBase + (long) lineIdxCount * Integer.BYTES;
        memPutInt(p,     i1);
        memPutInt(p + 4, i2);
        lineIdxCount += 2;
    }

    /** Append a triangle vertex (POSITION_COLOR) and return its index. */
    private static int putTriVert(float x, float y, float z, int r, int g, int b, int a) {
        long p = triVertsBase + (long) triVertI * VERTEX_SIZE;
        memPutFloat(p,      x);
        memPutFloat(p + 4,  y);
        memPutFloat(p + 8,  z);
        memPutInt(p + 12, ((a & 0xFF) << 24) | ((b & 0xFF) << 16) | ((g & 0xFF) << 8) | (r & 0xFF));
        return triVertI++;
    }

    /** Append a quad as two triangles (6 indices). */
    private static void putTriQuad(int i1, int i2, int i3, int i4) {
        long p = triIdxsBase + (long) triIdxCount * Integer.BYTES;
        memPutInt(p,      i1);
        memPutInt(p + 4,  i2);
        memPutInt(p + 8,  i3);
        memPutInt(p + 12, i3);
        memPutInt(p + 16, i4);
        memPutInt(p + 20, i1);
        triIdxCount += 6;
    }

    // ═════════════════════════════════════════════════════════
    //  PUBLIC DRAW API
    //  All positions are camera-relative (world minus cameraPos).
    // ═════════════════════════════════════════════════════════

    // ─── 3D Line ─────────────────────────────────────────────

    public static void drawLine3D(MatrixStack matrices, Vec3d start, Vec3d end,
                                  int r, int g, int b, int a) {

        if (currentLineWidth <= 1.0f) {
            ensureLineCapacity(2, 2);
            int i1 = putLineVert((float) start.x, (float) start.y, (float) start.z, r, g, b, a);
            int i2 = putLineVert((float) end.x,   (float) end.y,   (float) end.z,   r, g, b, a);
            putLineIdx(i1, i2);
        } else {
            // Fake line width for MacOS (Core Profile strictly limits glLineWidth to 1.0)
            int lines = Math.max(1, (int) Math.ceil(currentLineWidth));

            // We want screen width to match currentLineWidth.
            // A multiplier of ~0.002 to 0.003 usually bridges 1 block distance to 1 pixel.
            double k = 0.0025 * (currentLineWidth / 2.0);

            ensureLineCapacity(lines * 2 + 2, lines * 2 + 2);

            // Compute an orthogonal vector to the line direction
            Vec3d dir = end.subtract(start).normalize();
            // Pick an arbitrary up vector not collinear with dir
            Vec3d up = Math.abs(dir.y) < 0.99 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
            Vec3d right = dir.crossProduct(up).normalize();
            Vec3d trueUp = right.crossProduct(dir).normalize();

            double distStart = start.length();
            double distEnd = end.length();

            for (int i = 0; i < lines; i++) {
                // Determine a spiral or cross pattern offset based on the line index
                double angle = (Math.PI * 2.0 * i) / lines;
                double baseX = right.x * Math.cos(angle) + trueUp.x * Math.sin(angle);
                double baseY = right.y * Math.cos(angle) + trueUp.y * Math.sin(angle);
                double baseZ = right.z * Math.cos(angle) + trueUp.z * Math.sin(angle);

                // Multiply by camera distance to cancel out perspective scaling!
                // This keeps the fake tube a perfect constant 2D width on-screen.
                double ox1 = baseX * k * distStart;
                double oy1 = baseY * k * distStart;
                double oz1 = baseZ * k * distStart;

                double ox2 = baseX * k * distEnd;
                double oy2 = baseY * k * distEnd;
                double oz2 = baseZ * k * distEnd;

                int i1 = putLineVert((float) (start.x + ox1), (float) (start.y + oy1), (float) (start.z + oz1), r, g, b, a);
                int i2 = putLineVert((float) (end.x + ox2),   (float) (end.y + oy2),   (float) (end.z + oz2),   r, g, b, a);
                putLineIdx(i1, i2);
            }
            // Always draw the center line too
            int c1 = putLineVert((float) start.x, (float) start.y, (float) start.z, r, g, b, a);
            int c2 = putLineVert((float) end.x,   (float) end.y,   (float) end.z,   r, g, b, a);
            putLineIdx(c1, c2);
        }
    }

    public static void drawLine3D(MatrixStack matrices, Vec3d start, Vec3d end, int color) {
        int a = (color >> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8)  & 0xFF;
        int b =  color        & 0xFF;
        drawLine3D(matrices, start, end, r, g, b, a);
    }

    // ─── 3D Box (Wireframe) ──────────────────────────────────

    public static void drawBox3D(MatrixStack matrices, Box box, int r, int g, int b, int a) {
        ensureLineCapacity(8, 24);

        float x1 = (float) box.minX, y1 = (float) box.minY, z1 = (float) box.minZ;
        float x2 = (float) box.maxX, y2 = (float) box.maxY, z2 = (float) box.maxZ;

        int blb = putLineVert(x1, y1, z1, r, g, b, a);
        int blf = putLineVert(x1, y1, z2, r, g, b, a);
        int brb = putLineVert(x2, y1, z1, r, g, b, a);
        int brf = putLineVert(x2, y1, z2, r, g, b, a);
        int tlb = putLineVert(x1, y2, z1, r, g, b, a);
        int tlf = putLineVert(x1, y2, z2, r, g, b, a);
        int trb = putLineVert(x2, y2, z1, r, g, b, a);
        int trf = putLineVert(x2, y2, z2, r, g, b, a);

        // Bottom
        putLineIdx(blb, blf); putLineIdx(blf, brf);
        putLineIdx(brf, brb); putLineIdx(brb, blb);
        // Top
        putLineIdx(tlb, tlf); putLineIdx(tlf, trf);
        putLineIdx(trf, trb); putLineIdx(trb, tlb);
        // Verticals
        putLineIdx(blb, tlb); putLineIdx(blf, tlf);
        putLineIdx(brb, trb); putLineIdx(brf, trf);
    }

    public static void drawBox3D(MatrixStack matrices, Box box, int color) {
        int a = (color >> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8)  & 0xFF;
        int b =  color        & 0xFF;
        drawBox3D(matrices, box, r, g, b, a);
    }

    // ─── 3D Box (Filled) ─────────────────────────────────────

    public static void drawFilledBox3D(MatrixStack matrices, Box box,
                                       int r, int g, int b, int a) {
        ensureTriCapacity(8, 36);

        float x1 = (float) box.minX, y1 = (float) box.minY, z1 = (float) box.minZ;
        float x2 = (float) box.maxX, y2 = (float) box.maxY, z2 = (float) box.maxZ;

        int blb = putTriVert(x1, y1, z1, r, g, b, a);
        int blf = putTriVert(x1, y1, z2, r, g, b, a);
        int brb = putTriVert(x2, y1, z1, r, g, b, a);
        int brf = putTriVert(x2, y1, z2, r, g, b, a);
        int tlb = putTriVert(x1, y2, z1, r, g, b, a);
        int tlf = putTriVert(x1, y2, z2, r, g, b, a);
        int trb = putTriVert(x2, y2, z1, r, g, b, a);
        int trf = putTriVert(x2, y2, z2, r, g, b, a);

        putTriQuad(blb, brb, brf, blf);  // Bottom
        putTriQuad(tlb, tlf, trf, trb);  // Top
        putTriQuad(blb, tlb, trb, brb);  // North
        putTriQuad(blf, brf, trf, tlf);  // South
        putTriQuad(blb, blf, tlf, tlb);  // West
        putTriQuad(brb, trb, trf, brf);  // East
    }

    // ─── 3D Circle (Wireframe) ───────────────────────────────

    public static void drawCircle3D(MatrixStack matrices,
                                    double centerX, double centerY, double centerZ,
                                    double radius, int segments,
                                    int r, int g, int b, int a) {
        ensureLineCapacity(segments, segments * 2);

        int[] vi = new int[segments];
        for (int i = 0; i < segments; i++) {
            double ang = (2.0 * Math.PI * i) / segments;
            vi[i] = putLineVert(
                    (float) (centerX + Math.cos(ang) * radius),
                    (float) centerY,
                    (float) (centerZ + Math.sin(ang) * radius),
                    r, g, b, a);
        }
        for (int i = 0; i < segments; i++) {
            putLineIdx(vi[i], vi[(i + 1) % segments]);
        }
    }

    // ─── 3D Circle (Filled) ─────────────────────────────────

    public static void drawFilledCircle3D(MatrixStack matrices,
                                          double centerX, double centerY, double centerZ,
                                          double radius, int segments,
                                          int r, int g, int b, int a) {
        ensureTriCapacity(segments + 1, segments * 3);

        int center = putTriVert((float) centerX, (float) centerY, (float) centerZ, r, g, b, a);

        int[] rim = new int[segments];
        for (int i = 0; i < segments; i++) {
            double ang = (2.0 * Math.PI * i) / segments;
            rim[i] = putTriVert(
                    (float) (centerX + Math.cos(ang) * radius),
                    (float) centerY,
                    (float) (centerZ + Math.sin(ang) * radius),
                    r, g, b, a);
        }
        for (int i = 0; i < segments; i++) {
            int next = (i + 1) % segments;
            long p = triIdxsBase + (long) triIdxCount * Integer.BYTES;
            memPutInt(p,     center);
            memPutInt(p + 4, rim[i]);
            memPutInt(p + 8, rim[next]);
            triIdxCount += 3;
        }
    }

    // ─── World to Screen ─────────────────────────────────────

    public static int[] worldToScreen(Vec3d worldPos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.gameRenderer == null)
            return null;

        Camera camera = mc.gameRenderer.getCamera();
        Vec3d cameraPos = camera.getCameraPos();
        Vec3d relative = worldPos.subtract(cameraPos);

        Matrix4f viewMatrix = new Matrix4f();
        viewMatrix.identity();
        viewMatrix.rotate(camera.getRotation());

        Matrix4f projMatrix = mc.gameRenderer.getBasicProjectionMatrix(
                mc.options.getFov().getValue().floatValue());

        Matrix4f mvp = new Matrix4f(projMatrix).mul(viewMatrix);

        org.joml.Vector4f pos = new org.joml.Vector4f(
                (float) relative.x, (float) relative.y, (float) relative.z, 1.0f);
        pos.mul(mvp);

        if (pos.w <= 0.0f) return null;

        float ndcX = pos.x / pos.w;
        float ndcY = pos.y / pos.w;

        int screenW = mc.getWindow().getScaledWidth();
        int screenH = mc.getWindow().getScaledHeight();

        int screenX = (int) ((ndcX + 1.0f) / 2.0f * screenW);
        int screenY = (int) ((1.0f - ndcY) / 2.0f * screenH);

        return new int[] { screenX, screenY };
    }

    // ─── Camera helper ───────────────────────────────────────

    public static Vec3d getCameraPos() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.gameRenderer == null) return Vec3d.ZERO;
        return mc.gameRenderer.getCamera().getCameraPos();
    }
}
