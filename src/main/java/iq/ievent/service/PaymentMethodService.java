package iq.ievent.service;

import iq.ievent.domain.Organization;
import iq.ievent.domain.PaymentMethod;
import iq.ievent.repo.PaymentMethodRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Direct-payment methods a host offers at checkout (each with optional QR/photo). */
@Service
public class PaymentMethodService {

    private final PaymentMethodRepository methods;
    private final MessageSource messages;
    private final java.nio.file.Path uploadDir;

    public PaymentMethodService(PaymentMethodRepository methods,
                                MessageSource messages,
                                @Value("${app.upload-dir:/app/data/uploads}") String uploadDir) {
        this.methods = methods;
        this.messages = messages;
        this.uploadDir = java.nio.file.Path.of(uploadDir);
    }

    /** Localized user-facing message in the current request locale. */
    private String msg(String code, Object... args) {
        return messages.getMessage(code, args, LocaleContextHolder.getLocale());
    }

    @Transactional(readOnly = true)
    public List<PaymentMethod> forOrganization(Long orgId) {
        return methods.findByOrganizationIdOrderBySortOrderAscIdAsc(orgId);
    }

    @Transactional(readOnly = true)
    public List<PaymentMethod> enabledForOrganization(Long orgId) {
        return methods.findByOrganizationIdAndEnabledTrueOrderBySortOrderAscIdAsc(orgId);
    }

    /** Finds the org's cash-on-arrival method (at most one per org), if any. */
    @Transactional(readOnly = true)
    public PaymentMethod cashMethod(Long orgId) {
        return forOrganization(orgId).stream().filter(PaymentMethod::isCash).findFirst().orElse(null);
    }

    /** Turns cash-on-arrival on/off for the org — creates the (single, fixed)
     *  cash method row on first enable, otherwise just toggles it like any
     *  other method. Its label/instructions render from i18n at display time,
     *  not from the stored row, so what's saved here is just a marker. */
    @Transactional
    public void setCashEnabled(Organization org, boolean enable) {
        PaymentMethod cash = cashMethod(org.getId());
        if (cash == null) {
            if (!enable) return;
            cash = new PaymentMethod();
            cash.setOrganization(org);
            cash.setLabel("Cash");
            cash.setMethodType("CASH");
            cash.setSortOrder(forOrganization(org.getId()).size());
        }
        cash.setEnabled(enable);
        methods.save(cash);
    }

    /** Adds a method. Returns an error message or null on success. */
    @Transactional
    public String add(Organization org, String label, String accountNumber, String accountName,
                      String instructions, MultipartFile qrImage) {
        if (label == null || label.isBlank()) return msg("pm.nameRequired");
        if ((accountNumber == null || accountNumber.isBlank())
                && (qrImage == null || qrImage.isEmpty())) {
            return msg("pm.accountOrQrRequired");
        }
        PaymentMethod m = new PaymentMethod();
        m.setOrganization(org);
        m.setLabel(label.trim());
        m.setAccountNumber(blankToNull(accountNumber));
        m.setAccountName(blankToNull(accountName));
        m.setInstructions(blankToNull(instructions));
        m.setSortOrder(forOrganization(org.getId()).size());
        m = methods.save(m);
        if (qrImage != null && !qrImage.isEmpty()) {
            String err = storeQr(m, qrImage);
            if (err != null) {
                methods.delete(m);
                return err;
            }
        }
        return null;
    }

    /** Replaces/sets the QR image on an existing method. Returns error or null. */
    @Transactional
    public String updateQr(Long methodId, Long orgId, MultipartFile qrImage) {
        PaymentMethod m = owned(methodId, orgId);
        if (m == null) return msg("pm.unknown");
        return storeQr(m, qrImage);
    }

    @Transactional
    public void toggle(Long methodId, Long orgId) {
        PaymentMethod m = owned(methodId, orgId);
        if (m != null) {
            m.setEnabled(!m.isEnabled());
            methods.save(m);
        }
    }

    @Transactional
    public void delete(Long methodId, Long orgId) {
        PaymentMethod m = owned(methodId, orgId);
        if (m != null) {
            if (m.getQrImagePath() != null) {
                try { java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(m.getQrImagePath())); }
                catch (Exception ignored) { }
            }
            methods.delete(m);
        }
    }

    private PaymentMethod owned(Long methodId, Long orgId) {
        return methods.findById(methodId)
                .filter(m -> m.getOrganization().getId().equals(orgId))
                .orElse(null);
    }

    private String storeQr(PaymentMethod m, MultipartFile qrImage) {
        if (qrImage == null || qrImage.isEmpty()) return null;
        if (qrImage.getSize() > 2 * 1024 * 1024) return msg("pm.qr.tooLarge");
        String original = qrImage.getOriginalFilename() == null ? "" : qrImage.getOriginalFilename();
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!java.util.Set.of("jpg", "jpeg", "png", "webp").contains(ext)) {
            return msg("pm.qr.badType");
        }
        try {
            java.nio.file.Path dir = uploadDir.resolve("payment-qr");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path target = dir.resolve("method-" + m.getId() + "." + ext);
            java.nio.file.Files.copy(qrImage.getInputStream(), target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            m.setQrImagePath(target.toString());
            methods.save(m);
            return null;
        } catch (java.io.IOException e) {
            return msg("pm.qr.storeFailed");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
