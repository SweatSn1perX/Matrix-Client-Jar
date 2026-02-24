package net.matrix.systems.modules;

import net.matrix.events.EventBus;
import net.matrix.events.KeyEvent;
import net.matrix.events.TickEvent;
import net.matrix.systems.modules.general.*;
import net.matrix.systems.modules.mace.*;
import net.matrix.systems.modules.visual.*;
import java.util.*;

public class Modules {
    private static Modules instance;
    private final Map<Class<? extends Module>, Module> moduleInstances = new HashMap<>();
    private final List<Module> modules = new ArrayList<>();

    public Modules() {
        instance = this;
    }

    public static Modules get() {
        return instance;
    }

    public void init() {
        // Combat
        add(new AimAssist());
        add(new Reach());

        // General
        add(new AutoTotem());

        // Movement
        add(new Velocity());

        // Render
        add(new SelfDestruct());

        // Mace
        add(new KeyPearl());
        add(new KeyWindCharge());
        add(new WCPearl());
        add(new PearlBurst());
        add(new AutoStunSlam());
        add(new AutoHit());
        add(new MaceAimAssist());

        add(new WindChargeClutch());

        // Visual
        add(new MaceVisualizer());
        add(new Trajectories());
        add(new TargetHUD());
        add(new Crosshair());
    }

    private void add(Module module) {
        moduleInstances.put(module.getClass(), module);
        modules.add(module);
    }

    public void onTick() {
        // Fire the TickEvent through the bus (modules with @EventHandler receive it)
        EventBus.get().post(TickEvent.INSTANCE);

        // Legacy fallback: still call onTick() for modules that haven't migrated
        for (Module module : modules) {
            if (module.isActive()) {
                module.onTick();
            }
        }
    }

    public void onKey(int key, int action) {
        if (key == -1 || action != org.lwjgl.glfw.GLFW.GLFW_PRESS)
            return;

        // Fire KeyEvent through the bus
        EventBus.get().post(new KeyEvent(key, action));

        SelfDestruct sd = Modules.get().get(SelfDestruct.class);
        boolean locking = sd != null && sd.isLocking();

        for (Module module : modules) {
            if (module.getKey() == key) {
                if (locking && module != sd)
                    continue;
                module.toggle();
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends Module> T get(Class<T> klass) {
        return (T) moduleInstances.get(klass);
    }

    public Collection<Module> getAll() {
        return modules;
    }
}
