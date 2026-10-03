package dev.aegis.notifications.domain.push;

import dev.aegis.shared.MemberId;
import java.util.Optional;

/** Persistence of the members' push preferences; a member without a row uses the defaults. */
public interface PushPreferencesRepository {
    Optional<PushPreferences> findByMember(MemberId member);

    /**
     * Stores the preferences if the stored version still is the one they were changed from.
     *
     * @return false when another change was stored meanwhile
     */
    boolean save(PushPreferences preferences, long previousVersion);
}
