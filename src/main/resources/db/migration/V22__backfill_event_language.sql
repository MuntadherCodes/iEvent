-- Event.language was added (V?) as a column that nothing ever actually set,
-- so every row defaulted to 'en' regardless of what language the title was
-- really written in. Now that it drives auto-translation (see
-- GoogleTranslateService / HostService#translateEventContent), backfill it
-- from the title's own script — Arabic vs Latin is unambiguous letter by
-- letter, unlike a locale guess, and this platform is strictly bilingual.
UPDATE events SET language = 'ar' WHERE title ~ '[؀-ۿ]' AND language <> 'ar';
