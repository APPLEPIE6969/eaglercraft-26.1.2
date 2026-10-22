package net.minecraft;

/**
 * Minimal stub of MC 26.1.2's DetectedVersion for the platform-layer build.
 * Mirrors the surface SharedConstants relies on: a BUILT_IN constant and
 * createBuiltIn(...). Real version metadata is hardcoded by the platform.
 */
public class DetectedVersion {
    public static final WorldVersion BUILT_IN = new WorldVersion();

    public static WorldVersion createBuiltIn(String id, String name, boolean stable) {
        return BUILT_IN;
    }
}
