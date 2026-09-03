package iq.ievent.service;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import iq.ievent.domain.Event;
import iq.ievent.domain.Order;
import iq.ievent.domain.Ticket;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;

/**
 * Branded, printable ticket PDFs (one page per ticket).
 *
 * R31 #12: rendered in the reader's language. DejaVu Sans (embedded, covers
 * Arabic + Latin) replaces Helvetica, and every text block goes through a
 * PdfPCell with an explicit run direction, which is what makes OpenPDF apply
 * Arabic shaping and bidi ordering. The event title is shown in the reader's
 * language with the other-language version underneath when a translation
 * exists, so a mixed Arabic/English audience can always read the ticket.
 */
@Service
public class TicketPdfService {

    private static final Color INK = new Color(0x23, 0x22, 0x2F);
    private static final Color BRAND = new Color(0x8F, 0x7A, 0xC9);
    private static final Color MUTED = new Color(0x6B, 0x6A, 0x80);

    private final QrService qr;
    private final MessageSource messages;
    private final BaseFont regular;
    private final BaseFont bold;

    public TicketPdfService(QrService qr, MessageSource messages) {
        this.qr = qr;
        this.messages = messages;
        this.regular = load("fonts/DejaVuSans.ttf");
        this.bold = load("fonts/DejaVuSans-Bold.ttf");
    }

    private static BaseFont load(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, in.readAllBytes(), null);
        } catch (Exception e) {
            throw new IllegalStateException("Ticket font missing: " + path, e);
        }
    }

    public byte[] ticketsPdf(List<Ticket> tickets) {
        return ticketsPdf(tickets, LocaleContextHolder.getLocale());
    }

    public byte[] ticketsPdf(List<Ticket> tickets, Locale locale) {
        Locale loc = locale != null && "ar".equals(locale.getLanguage()) ? new Locale("ar") : Locale.ENGLISH;
        try {
            Document doc = new Document(PageSize.A5, 36, 36, 36, 36);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(doc, out);
            doc.open();

            boolean first = true;
            for (Ticket t : tickets) {
                if (!first) doc.newPage();
                first = false;
                addTicketPage(doc, t, loc);
            }
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Ticket PDF generation failed", e);
        }
    }

    private Font font(float size, boolean isBold, Color color) {
        return new Font(isBold ? bold : regular, size, Font.NORMAL, color);
    }

    private String msg(String key, Locale loc, Object... args) {
        return messages.getMessage(key, args, loc);
    }

    private void addTicketPage(Document doc, Ticket t, Locale loc) throws Exception {
        Event e = t.getEvent();
        boolean rtl = "ar".equals(loc.getLanguage());
        int dir = rtl ? PdfWriter.RUN_DIRECTION_RTL : PdfWriter.RUN_DIRECTION_LTR;

        // Whole page is one column of direction-aware cells.
        PdfPTable page = new PdfPTable(1);
        page.setWidthPercentage(100);
        page.setRunDirection(dir);

        // Brand line always LTR (wordmark + domain are Latin)
        Paragraph brand = new Paragraph();
        brand.add(new Chunk("i", font(18, true, BRAND)));
        brand.add(new Chunk("Event", font(18, true, INK)));
        brand.add(new Chunk("   ievent.events", font(9, false, MUTED)));
        page.addCell(cell(brand, PdfWriter.RUN_DIRECTION_LTR, 0, 6, Element.ALIGN_LEFT));

        // Title in the reader's language, other language underneath when translated
        String primary = Format.localized(e.getTitle(), e.getTitleTranslated(), e.getLanguage());
        String secondary = e.getTitleTranslated() == null || e.getTitleTranslated().isBlank()
                || e.getTitleTranslated().equals(primary) ? null
                : (primary.equals(e.getTitle()) ? e.getTitleTranslated() : e.getTitle());
        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setPadding(0);
        titleCell.setPaddingBottom(4);
        titleCell.setRunDirection(dir);
        Paragraph title = new Paragraph(primary, font(16, true, INK));
        title.setLeading(20);
        title.setAlignment(Element.ALIGN_LEFT);
        titleCell.addElement(title);
        if (secondary != null) {
            boolean secondaryArabic = HostService.containsArabic(secondary);
            Paragraph sub = new Paragraph(secondary, font(11, false, MUTED));
            sub.setLeading(14);
            sub.setAlignment(Element.ALIGN_LEFT);
            PdfPCell subCell = new PdfPCell();
            subCell.setBorder(Rectangle.NO_BORDER);
            subCell.setPadding(0);
            subCell.setRunDirection(secondaryArabic ? PdfWriter.RUN_DIRECTION_RTL : PdfWriter.RUN_DIRECTION_LTR);
            subCell.addElement(sub);
            PdfPTable subTable = new PdfPTable(1);
            subTable.setWidthPercentage(100);
            subTable.addCell(subCell);
            titleCell.addElement(subTable);
        }
        page.addCell(titleCell);

        String dateLine = Format.longDateLine(e.getStartsAt(), e.getEndsAt(), e.isHasStartTime(), e.getDatePrecision(), loc);
        page.addCell(cell(new Paragraph(dateLine, font(10, false, MUTED)), dir, 0, 1, alignFor(rtl)));
        String where = "ONLINE".equals(e.getLocationType()) ? msg("location.online", loc)
                : "TBA".equals(e.getLocationType()) ? msg("location.tba", loc)
                : (e.getVenueName() == null ? "" : e.getVenueName() + ", ") + Cities.label(e.getCity(), loc);
        page.addCell(cell(new Paragraph(where, font(10, false, MUTED)), dir, 0, 10, alignFor(rtl)));

        // Details + QR side by side (QR ends up on the outer side in both directions)
        // addCell reverses cell ORDER for RTL rows but not the column widths, so
        // the widths are pre-mirrored to keep the QR in the narrower outer column.
        PdfPTable table = new PdfPTable(rtl ? new float[]{1f, 1.4f} : new float[]{1.4f, 1f});
        table.setWidthPercentage(100);
        table.setRunDirection(dir);

        PdfPCell details = new PdfPCell();
        details.setBorder(Rectangle.NO_BORDER);
        details.setPadding(0);
        details.setPaddingTop(8);
        details.setRunDirection(dir);
        details.addElement(labeled(msg("pdf.holder", loc), t.getHolderName(), rtl));
        details.addElement(labeled(msg("pdf.type", loc), t.getTicketType().getName(), rtl));
        details.addElement(labeled(msg("pdf.code", loc), t.getCode(), rtl));
        details.addElement(labeled(msg("pdf.status", loc), t.getStatus() == Ticket.Status.CHECKED_IN
                ? msg("pdf.checkedIn", loc) : t.getStatus() == Ticket.Status.VOID
                ? msg("pdf.void", loc) : msg("pdf.valid", loc), rtl));
        if (t.getOrder().getPaymentMethod() == Order.PaymentMethod.CASH) {
            details.addElement(labeled(msg("pdf.payment", loc), msg("pdf.cash", loc), rtl));
        }
        table.addCell(details);

        Image qrImage = Image.getInstance(qr.ticketQrPng(t.getCode()));
        qrImage.scaleToFit(150, 150);
        // fit=true draws the image directly (no text chunk, so no Helvetica gets registered)
        PdfPCell qrCell = new PdfPCell(qrImage, true);
        qrCell.setBorder(Rectangle.NO_BORDER);
        qrCell.setPadding(0);
        qrCell.setFixedHeight(150);
        qrCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(qrCell);

        PdfPCell tableCell = new PdfPCell(table);
        tableCell.setBorder(Rectangle.NO_BORDER);
        tableCell.setPadding(0);
        page.addCell(tableCell);

        page.addCell(cell(new Paragraph(msg("pdf.showQr", loc), font(9, false, MUTED)), dir, 10, 2, alignFor(rtl)));
        Paragraph verify = new Paragraph();
        verify.add(new Chunk(msg("pdf.verifyAt", loc) + " ", font(8, false, MUTED)));
        verify.add(new Chunk(qr.ticketUrl(t.getCode()), font(8, false, MUTED)));
        page.addCell(cell(verify, dir, 0, 0, alignFor(rtl)));

        doc.add(page);
    }

    /** OpenPDF mirrors alignment under RUN_DIRECTION_RTL: ALIGN_LEFT means "start"
     *  in both directions, so the same constant gives flush-right Arabic. */
    private static int alignFor(boolean rtl) {
        return Element.ALIGN_LEFT;
    }

    private static PdfPCell cell(Paragraph p, int runDirection, float padTop, float padBottom, int align) {
        p.setAlignment(align);
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(0);
        c.setPaddingTop(padTop);
        c.setPaddingBottom(padBottom);
        c.setRunDirection(runDirection);
        c.addElement(p);
        return c;
    }

    private Paragraph labeled(String label, String value, boolean rtl) {
        Paragraph p = new Paragraph();
        p.setSpacingAfter(7);
        p.setLeading(14);
        p.setAlignment(alignFor(rtl));
        p.add(new Chunk(label + "\n", font(7, true, MUTED)));
        p.add(new Chunk(value == null ? "-" : value, font(12, false, INK)));
        return p;
    }
}
