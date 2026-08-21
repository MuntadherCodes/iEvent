package iq.ievent.web;

import iq.ievent.service.Cities;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Template helper bean — reach it as ${@t.…} from any template.
 * Centralizes localized lookups that don't fit #{static.keys}:
 * city labels for stored English city names, the full governorate list
 * for selects, and programmatic message lookups.
 */
@Component("t")
public class LocalizationHelper {

    private final MessageSource messages;

    public LocalizationHelper(MessageSource messages) {
        this.messages = messages;
    }

    /** Localized display label for a canonical (English) city name. */
    public String city(String canonicalEn) {
        return Cities.label(canonicalEn, LocaleContextHolder.getLocale());
    }

    /** All 19 governorates for city selects (value = canonical en). */
    public List<Cities.City> cities() {
        return Cities.ALL;
    }

    /** True when rendering Arabic (RTL). */
    public boolean rtl() {
        return !"en".equals(LocaleContextHolder.getLocale().getLanguage());
    }

    /** Platform booking fee for one ticket at this price — see Format.bookingFeeFor. */
    public long bookingFee(long priceIqd) {
        return iq.ievent.service.Format.bookingFeeFor(priceIqd);
    }

    /** Localized label for an Event.Category enum name (e.g. "MUSIC" → "موسيقى"). */
    public String category(String enumName) {
        try {
            return iq.ievent.service.Format.categoryLabel(
                    iq.ievent.domain.Event.Category.valueOf(enumName));
        } catch (Exception e) {
            return enumName;
        }
    }

    /** Programmatic message lookup with the current locale. */
    public String msg(String code, Object... args) {
        return messages.getMessage(code, args, LocaleContextHolder.getLocale());
    }
}
