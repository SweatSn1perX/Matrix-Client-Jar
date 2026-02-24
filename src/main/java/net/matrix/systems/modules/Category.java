package net.matrix.systems.modules;

public enum Category {
    MACE("Mace"),
    GENERAL("General"),
    VISUAL("Visual"),
    GUI("GUI");

    public final String name;

    Category(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
