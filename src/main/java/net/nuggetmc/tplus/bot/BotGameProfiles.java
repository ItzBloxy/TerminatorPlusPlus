package net.nuggetmc.tplus.bot;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import java.util.UUID;

/**
 * Builds {@link GameProfile}s for bots.
 *
 * <p>The Paper build subclassed GameProfile (CustomGameProfile). Since authlib 9.x
 * GameProfile is a {@code final record}, so this is a factory instead. Accessors also
 * moved: {@code getProperties()} is now {@code properties()}.
 */
public final class BotGameProfiles {

    private BotGameProfiles() {
    }

    /**
     * @param skin a {value, signature} texture pair, or null for the default skin
     */
    public static GameProfile create(String name, String[] skin) {
        return create(randomSteveUuid(), name, skin);
    }

    /**
     * authlib 9.x changed both types here: PropertyMap is a ForwardingMultimap with no
     * no-arg constructor, so it must wrap a backing multimap, and Property is a record.
     * The backing map is mutable because vanilla may add properties to a profile later.
     */
    public static GameProfile create(UUID uuid, String name, String[] skin) {
        Multimap<String, Property> backing = LinkedHashMultimap.create();

        if (skin != null && skin.length == 2 && skin[0] != null) {
            backing.put("textures", new Property("textures", skin[0], skin[1]));
        }

        return new GameProfile(uuid, trim16(name), new PropertyMap(backing));
    }

    /**
     * A random UUID whose hash is even.
     *
     * <p>Ported from {@code BotUtils.randomSteveUUID}. When a profile carries no skin
     * texture, the client picks the default model from the UUID's hash parity: even
     * gives Steve, odd gives Alex. Constraining the hash keeps skinless bots visually
     * consistent instead of randomly alternating. The upstream version recursed; this
     * loops, which is the same thing without the stack.
     */
    public static UUID randomSteveUuid() {
        UUID uuid = UUID.randomUUID();

        while (uuid.hashCode() % 2 != 0) {
            uuid = UUID.randomUUID();
        }

        return uuid;
    }

    /** Minecraft rejects names longer than 16 characters. */
    public static String trim16(String name) {
        return name.length() > 16 ? name.substring(0, 16) : name;
    }
}
