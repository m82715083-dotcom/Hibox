package com.example.bbvisualizer;

// ─── Fabric / Minecraft API ───────────────────────────────────────────────────
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.*;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * BoundingBoxVisualizer — учебный Fabric-мод для Minecraft 1.20.1.
 *
 * Всё объединено в один класс: инициализация, логика клавиш,
 * изменение BoundingBox и wireframe-рендер.
 *
 * Управление:
 *   ↑  — увеличить collisionOffset на 0.1
 *   ↓  — уменьшить collisionOffset на 0.1
 *
 * Поведение:
 *   • Все LivingEntity в радиусе 10 блоков получают expand(collisionOffset).
 *   • Если сущность дальше 5 блоков — хитбокс сбрасывается до стандартного,
 *     чтобы избежать артефактов рендеринга на дистанции.
 *   • Wireframe: зелёный = расширен, красный = сброшен.
 *   • Текущее значение offset выводится в action bar (над хотбаром).
 */
@Environment(EnvType.CLIENT)
public class BoundingBoxVisualizer implements ClientModInitializer {

    // ── Константы ─────────────────────────────────────────────────────────────

    public static final String MOD_ID = "bbvisualizer";
    public static final Logger LOGGER  = LoggerFactory.getLogger(MOD_ID);

    private static final double OFFSET_STEP    = 0.1;   // шаг изменения
    private static final double OFFSET_MIN     = -0.4;  // нижняя граница
    private static final double OFFSET_MAX     = 2.0;   // верхняя граница
    private static final double SEARCH_RADIUS  = 10.0;  // радиус поиска сущностей
    private static final double RESET_DISTANCE = 5.0;   // порог сброса хитбокса

    // ── Изменяемое состояние ──────────────────────────────────────────────────

    private static double collisionOffset = 0.0;

    private KeyBinding keyIncrease;
    private KeyBinding keyDecrease;

    /** Edge-trigger: реагируем только на новое нажатие, а не на удержание */
    private boolean wasIncreaseDown = false;
    private boolean wasDecreaseDown = false;

    // ─────────────────────────────────────────────────────────────────────────
    //  Инициализация
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onInitializeClient() {
        LOGGER.info("[BBVisualizer] Мод загружен. ↑↓ для управления collisionOffset.");
        registerKeyBindings();
        registerTickHandler();
        registerRenderer();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Регистрация клавиш
    // ─────────────────────────────────────────────────────────────────────────

    private void registerKeyBindings() {
        keyIncrease = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bbvisualizer.increase",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UP,
                "category.bbvisualizer"
        ));
        keyDecrease = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bbvisualizer.decrease",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_DOWN,
                "category.bbvisualizer"
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Тик: обработка ввода + изменение хитбоксов
    // ─────────────────────────────────────────────────────────────────────────

    private void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            // 1. Клавиши (срабатывают один раз при нажатии) ───────────────────
            boolean incDown = keyIncrease.isPressed();
            boolean decDown = keyDecrease.isPressed();

            if (incDown && !wasIncreaseDown) {
                collisionOffset = Math.min(collisionOffset + OFFSET_STEP, OFFSET_MAX);
                showOffset(client);
            }
            if (decDown && !wasDecreaseDown) {
                collisionOffset = Math.max(collisionOffset - OFFSET_STEP, OFFSET_MIN);
                showOffset(client);
            }

            wasIncreaseDown = incDown;
            wasDecreaseDown = decDown;

            // 2. Применяем / сбрасываем BoundingBox ───────────────────────────
            applyBoundingBoxes(client);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Логика BoundingBox
    // ─────────────────────────────────────────────────────────────────────────

    private static void applyBoundingBoxes(MinecraftClient client) {
        Box searchArea = client.player.getBoundingBox().expand(SEARCH_RADIUS);

        List<LivingEntity> nearby = client.world.getEntitiesByClass(
                LivingEntity.class,
                searchArea,
                entity -> entity != client.player
        );

        for (LivingEntity entity : nearby) {
            double dist = client.player.distanceTo(entity);

            if (dist > RESET_DISTANCE) {
                // Сбрасываем до стандартного хитбокса, чтобы не было
                // артефактов рендеринга на расстоянии
                entity.setBoundingBox(defaultBoxOf(entity));
            } else {
                // expand() расширяет Box симметрично по всем 6 граням
                entity.setBoundingBox(
                        entity.getBoundingBox().expand(collisionOffset)
                );
            }
        }
    }

    /**
     * Пересчитывает «эталонный» AABB из текущей позиции и размеров сущности.
     * Аналог недоступного снаружи Entity.calculateBoundingBox().
     */
    private static Box defaultBoxOf(LivingEntity entity) {
        double hw = entity.getWidth() / 2.0;    // half-width
        double h  = entity.getHeight();
        double x  = entity.getX();
        double y  = entity.getY();
        double z  = entity.getZ();
        return new Box(x - hw, y, z - hw,
                       x + hw, y + h, z + hw);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Action bar — текущее значение offset
    // ─────────────────────────────────────────────────────────────────────────

    private static void showOffset(MinecraftClient client) {
        if (client.player == null) return;
        // true → action bar (над хотбаром), не засоряет чат
        client.player.sendMessage(
                Text.literal(String.format(
                        "§e[BBVisualizer] §fCollisionOffset: §a%.1f §7(↑↓)",
                        collisionOffset)),
                true
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Wireframe-рендер хитбоксов
    // ─────────────────────────────────────────────────────────────────────────

    private static void registerRenderer() {
        WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null) return;

            MatrixStack matrices = ctx.matrixStack();
            Vec3d cam = ctx.camera().getPos();   // позиция камеры для смещения координат

            Box searchArea = client.player.getBoundingBox().expand(SEARCH_RADIUS);
            List<LivingEntity> nearby = client.world.getEntitiesByClass(
                    LivingEntity.class, searchArea, e -> e != client.player
            );

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();     // видим сквозь блоки
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);

            Tessellator tess = Tessellator.getInstance();
            BufferBuilder buf = tess.getBuffer();

            for (LivingEntity entity : nearby) {
                double dist = client.player.distanceTo(entity);
                Box box = entity.getBoundingBox();

                matrices.push();
                matrices.translate(-cam.x, -cam.y, -cam.z);
                Matrix4f mat = matrices.peek().getPositionMatrix();

                buf.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

                if (dist <= RESET_DISTANCE) {
                    drawBox(buf, mat, box, 0f, 1f, 0f, 1f);   // зелёный — расширен
                } else {
                    drawBox(buf, mat, box, 1f, 0f, 0f, 1f);   // красный — сброшен
                }

                tess.draw();
                matrices.pop();
            }

            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
        });
    }

    // ── Вспомогательные методы рендера ────────────────────────────────────────

    /** Рисует 12 рёбер AABB одним цветом RGBA. */
    private static void drawBox(BufferBuilder buf, Matrix4f m, Box b,
                                float r, float g, float bv, float a) {
        float x0 = (float)b.minX, y0 = (float)b.minY, z0 = (float)b.minZ;
        float x1 = (float)b.maxX, y1 = (float)b.maxY, z1 = (float)b.maxZ;
        // нижнее основание
        ln(buf,m, x0,y0,z0, x1,y0,z0, r,g,bv,a);
        ln(buf,m, x1,y0,z0, x1,y0,z1, r,g,bv,a);
        ln(buf,m, x1,y0,z1, x0,y0,z1, r,g,bv,a);
        ln(buf,m, x0,y0,z1, x0,y0,z0, r,g,bv,a);
        // верхнее основание
        ln(buf,m, x0,y1,z0, x1,y1,z0, r,g,bv,a);
        ln(buf,m, x1,y1,z0, x1,y1,z1, r,g,bv,a);
        ln(buf,m, x1,y1,z1, x0,y1,z1, r,g,bv,a);
        ln(buf,m, x0,y1,z1, x0,y1,z0, r,g,bv,a);
        // вертикальные стойки
        ln(buf,m, x0,y0,z0, x0,y1,z0, r,g,bv,a);
        ln(buf,m, x1,y0,z0, x1,y1,z0, r,g,bv,a);
        ln(buf,m, x1,y0,z1, x1,y1,z1, r,g,bv,a);
        ln(buf,m, x0,y0,z1, x0,y1,z1, r,g,bv,a);
    }

    private static void ln(BufferBuilder buf, Matrix4f m,
                            float x0, float y0, float z0,
                            float x1, float y1, float z1,
                            float r, float g, float b, float a) {
        buf.vertex(m, x0, y0, z0).color(r, g, b, a).next();
        buf.vertex(m, x1, y1, z1).color(r, g, b, a).next();
    }
}
