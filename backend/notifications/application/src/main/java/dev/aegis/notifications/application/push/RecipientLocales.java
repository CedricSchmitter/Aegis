package dev.aegis.notifications.application.push;

import dev.aegis.shared.MemberId;
import dev.aegis.shared.ProductLocale;

/** The product locale a member chose, provided by the identity context through the composition root. */
public interface RecipientLocales {
    ProductLocale localeOf(MemberId member);
}
