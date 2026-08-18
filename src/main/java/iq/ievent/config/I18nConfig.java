package iq.ievent.config;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.servlet.LocaleResolver;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.StandardLinkBuilder;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Locale;

/**
 * Wires the Arabic-first locale scheme into Spring MVC and Thymeleaf:
 * - LocaleResolver reads the locale the LocaleFilter resolved from the URL/cookie.
 * - The Thymeleaf link builder prefixes every internal @{...} URL with /en when
 *   rendering English, so language survives navigation without touching any
 *   template. (The LocaleFilter strips the prefix back off on the way in —
 *   even for assets, so no path needs to be excluded.)
 */
@Configuration
public class I18nConfig {

    private final SpringTemplateEngine templateEngine;

    public I18nConfig(SpringTemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
        LocaleContextHolder.setDefaultLocale(LocaleFilter.ARABIC);
    }

    @Bean
    public LocaleResolver localeResolver() {
        return new LocaleResolver() {
            @Override
            public Locale resolveLocale(HttpServletRequest request) {
                Object l = request.getAttribute(LocaleFilter.LOCALE_ATTR);
                return l instanceof Locale locale ? locale : LocaleFilter.ARABIC;
            }
            @Override
            public void setLocale(HttpServletRequest request,
                                  jakarta.servlet.http.HttpServletResponse response, Locale locale) {
                // locale is URL/cookie-driven; programmatic switches go via /set-lang
            }
        };
    }

    @PostConstruct
    public void installLinkBuilder() {
        templateEngine.setLinkBuilder(new StandardLinkBuilder() {
            @Override
            protected String computeContextPath(IExpressionContext context, String base,
                                                java.util.Map<String, Object> parameters) {
                String contextPath = super.computeContextPath(context, base, parameters);
                Locale locale = context.getLocale();
                if (locale != null && "en".equals(locale.getLanguage())
                        && base != null && base.startsWith("/") && !base.startsWith("/en/")
                        && !base.equals("/en")) {
                    return contextPath + "/en";
                }
                return contextPath;
            }
        });
    }
}
