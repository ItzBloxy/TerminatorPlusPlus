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
     * @param skin a {value, signature} texture pair, or null for the default skin. The signature
     *            half is itself allowed to be null — see {@code MojangSkins.SESSION_URL} for why
     *            bot textures are fetched unsigned.
     */
    public static GameProfile create(String name, String[] skin) {
        return create(randomSteveUuid(), name, skin);
    }

    /**
     * authlib 9.x changed both types here: PropertyMap is a ForwardingMultimap with no
     * no-arg constructor, so it must be handed a backing multimap, and Property is a
     * record.
     *
     * <p>The backing multimap is only a builder. {@code PropertyMap}'s constructor runs
     * {@code ImmutableMultimap.copyOf} on it, so the resulting profile's properties are
     * immutable no matter what is passed in — consistent with GameProfile being a record.
     * Any property a bot needs has to be set here, at construction.
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

    /**
     * Substitutes upstream's {@code %} index placeholder.
     *
     * <p>{@code BotManagerImpl.createBots} did {@code name.replace("%", i)} with {@code i}
     * starting at 1, so "Bot%" yields Bot1..BotN and a name without {@code %} yields N
     * bots that share a name. Extracted so the rule is testable without a world.
     */
    public static String indexedName(String template, int index) {
        return template.replace("%", String.valueOf(index));
    }

    /** Minecraft rejects names longer than 16 characters. */
    public static String trim16(String name) {
        return name.length() > 16 ? name.substring(0, 16) : name;
    }
}
