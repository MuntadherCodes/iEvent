package iq.ievent.service;

import iq.ievent.domain.Organization;
import iq.ievent.domain.PaymentMethod;
import iq.ievent.repo.PaymentMethodRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Direct-payment methods a host offers at checkout (each with optional QR/photo). */
@Service
public class PaymentMethodService {

    private final PaymentMethodRepository methods;
    private final java.nio.file.Path uploadDir;

    public PaymentMethodService(PaymentMethodRepository methods,
                                @Value("${app.upload-dir:/app/data/uploads}") String uploadDir) {
        this.methods = methods;
        this.uploadDir = java.nio.file.Path.of(uploadDir);
    }

    @Transactional(readOnly = true)
    public List<PaymentMethod> forOrganization(Long orgId) {
        return methods.findByOrganizationIdOrderBySortOrderAscIdAsc(orgId);
    }

    @Transactional(readOnly = true)
    public List<PaymentMethod> enabledForOrganization(Long orgId) {
        return methods.findByOrganizationIdAndEnabledTrueOrderBySortOrderAscIdAsc(orgId);
    }

    /** Adds a method. Returns an error message or null on success. */
    @Transactional
    public String add(Organization org, String label, String accountNumber, String accountName,
                      String instructions, MultipartFile qrImage) {
        if (label == null || label.isBlank()) return "Give the payment method a name (e.g. ZainCash).";
        if ((accountNumber == null || accountNumber.isBlank())
                && (qrImage == null || qrImage.isEmpty())) {
            return "Add an account/card number or upload a QR image — buyers need one of them.";
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
        if (m == null) return "Unknown payment method.";
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
        if (qrImage.getSize() > 2 * 1024 * 1024) return "QR image is too large (max 2 MB).";
        String original = qrImage.getOriginalFilename() == null ? "" : qrImage.getOriginalFilename();
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!java.util.Set.of("jpg", "jpeg", "png", "webp").contains(ext)) {
            return "QR must be a JPG, PNG or WEBP image.";
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
            return "Could not store the QR image — try again.";
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
