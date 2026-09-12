package net.nuggetmc.tplus.bot;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure tests: building a GameProfile needs authlib and guava, but no game bootstrap and
 * no world.
 *
 * <p>This covers the two things the authlib 9.x migration changed — GameProfile and
 * PropertyMap both stopped being subclassable/no-arg-constructible — plus the Steve
 * UUID parity rule, which looks arbitrary and is easy to "clean up" by accident.
 */
class BotGameProfilesTest {

    @Test
    void trim16LeavesShortNamesAlone() {
        assertEquals("Notch", BotGameProfiles.trim16("Notch"));
        assertEquals("ExactlySixteenXX", BotGameProfiles.trim16("ExactlySixteenXX"));
        assertEquals(16, "ExactlySixteenXX".length(), "fixture must be exactly at the limit");
    }

    @Test
    void trim16TruncatesOverlongNames() {
        assertEquals("ThisNameIsFarTo", BotGameProfiles.trim16("ThisNameIsFarToooLong").substring(0, 15));
        assertEquals(16, BotGameProfiles.trim16("ThisNameIsFarToooLong").length());
    }

    @Test
    void createdProfileUsesTheTrimmedName() {
        GameProfile profile = BotGameProfiles.create("AnExcessivelyLongBotName", null);

        assertEquals(16, profile.name().length());
        assertEquals("AnExcessivelyLon", profile.name());
    }

    @Test
    void aNullSkinLeavesTheProfileWithoutTextures() {
        GameProfile profile = BotGameProfiles.create("Skinless", null);

        assertTrue(profile.properties().get("textures").isEmpty(),
                "no skin was supplied, so no textures property should exist");
    }

    @Test
    void aSkinPairBecomesASignedTexturesProperty() {
        GameProfile profile = BotGameProfiles.create("Skinned", new String[]{"VALUE", "SIGNATURE"});

        Collection<Property> textures = profile.properties().get("textures");
        assertEquals(1, textures.size());

        Property property = textures.iterator().next();
        assertEquals("textures", property.name());
        assertEquals("VALUE", property.value());
        assertEquals("SIGNATURE", property.signature());
        assertTrue(property.hasSignature());
    }

    @Test
    void aMalformedSkinArrayIsIgnoredRatherThanThrowing() {
        assertTrue(BotGameProfiles.create("A", new String[]{}).properties().get("textures").isEmpty());
        assertTrue(BotGameProfiles.create("B", new String[]{"only-one"}).properties().get("textures").isEmpty());
        assertTrue(BotGameProfiles.create("C", new String[]{null, null}).properties().get("textures").isEmpty());
    }

    @Test
    void theResultingPropertyMapIsImmutable() {
        // PropertyMap's constructor runs ImmutableMultimap.copyOf on whatever backing
        // map it is given, so the multimap we build in the factory is only a builder and
        // the profile's properties can never be added to afterwards. Pinned because it
        // means any property a bot needs must be set at construction time.
        GameProfile profile = BotGameProfiles.create("Immutable", null);

        assertThrows(UnsupportedOperationException.class,
                () -> profile.properties().put("test", new Property("test", "v")));
    }

    @Test
    void randomSteveUuidAlwaysHasAnEvenHash() {
        // Upstream's randomSteveUUID constrains hash parity because the client picks the
        // default skin model from it: even is Steve, odd is Alex. Without this, skinless
        // bots alternate models at random.
        for (int i = 0; i < 500; i++) {
            UUID uuid = BotGameProfiles.randomSteveUuid();
            assertEquals(0, uuid.hashCode() % 2,
                    "randomSteveUuid must return an even-hash UUID, got " + uuid);
        }
    }

    @Test
    void randomSteveUuidDoesNotReturnTheSameValueEveryTime() {
        assertNotEquals(BotGameProfiles.randomSteveUuid(), BotGameProfiles.randomSteveUuid());
    }

    @Test
    void anExplicitUuidIsUsedVerbatim() {
        UUID fixed = UUID.fromString("00000000-0000-0000-0000-000000000001");
        GameProfile profile = BotGameProfiles.create(fixed, "Fixed", null);

        assertEquals(fixed, profile.id());
        assertEquals("Fixed", profile.name());
    }
}
