package iq.ievent.service;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import iq.ievent.domain.Event;
import iq.ievent.domain.Ticket;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;

/** Branded, printable ticket PDFs (one page per ticket). */
@Service
public class TicketPdfService {

    private static final Color INK = new Color(0x23, 0x22, 0x2F);
    private static final Color BRAND = new Color(0x8F, 0x7A, 0xC9);
    private static final Color MUTED = new Color(0x6B, 0x6A, 0x80);

    private final QrService qr;

    public TicketPdfService(QrService qr) {
        this.qr = qr;
    }

    public byte[] ticketsPdf(List<Ticket> tickets) {
        try {
            Document doc = new Document(PageSize.A5, 36, 36, 36, 36);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(doc, out);
            doc.open();

            boolean first = true;
            for (Ticket t : tickets) {
                if (!first) doc.newPage();
                first = false;
                addTicketPage(doc, t);
            }
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Ticket PDF generation failed", e);
        }
    }

    private void addTicketPage(Document doc, Ticket t) throws Exception {
        Event e = t.getEvent();

        Font brandFont = new Font(Font.HELVETICA, 18, Font.BOLD, INK);
        Font brandI = new Font(Font.HELVETICA, 18, Font.BOLD, BRAND);
        Paragraph brand = new Paragraph();
        brand.add(new Chunk("i", brandI));
        brand.add(new Chunk("Event", brandFont));
        brand.add(new Chunk("   ievent.iq", new Font(Font.HELVETICA, 9, Font.NORMAL, MUTED)));
        doc.add(brand);
        doc.add(new Paragraph(" ", new Font(Font.HELVETICA, 4)));

        doc.add(new Paragraph(e.getTitle(), new Font(Font.HELVETICA, 16, Font.BOLD, INK)));
        doc.add(new Paragraph(Format.longDateLine(e.getStartsAt(), e.getEndsAt()),
                new Font(Font.HELVETICA, 10, Font.NORMAL, MUTED)));
        doc.add(new Paragraph((e.getVenueName() == null ? "" : e.getVenueName() + ", ") + e.getCity(),
                new Font(Font.HELVETICA, 10, Font.NORMAL, MUTED)));
        doc.add(new Paragraph(" ", new Font(Font.HELVETICA, 6)));

        PdfPTable table = new PdfPTable(new float[]{1.4f, 1f});
        table.setWidthPercentage(100);

        PdfPCell details = new PdfPCell();
        details.setBorder(Rectangle.NO_BORDER);
        details.setPaddingTop(8);
        details.addElement(labeled("TICKET HOLDER", t.getHolderName()));
        details.addElement(labeled("TYPE", t.getTicketType().getName()));
        details.addElement(labeled("TICKET CODE", t.getCode()));
        details.addElement(labeled("STATUS", t.getStatus() == Ticket.Status.CHECKED_IN
                ? "Checked in" : "Valid"));
        table.addCell(details);

        Image qrImage = Image.getInstance(qr.ticketQrPng(t.getCode()));
        qrImage.scaleToFit(150, 150);
        PdfPCell qrCell = new PdfPCell(qrImage, false);
        qrCell.setBorder(Rectangle.NO_BORDER);
        qrCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(qrCell);
        doc.add(table);

        doc.add(new Paragraph(" ", new Font(Font.HELVETICA, 6)));
        doc.add(new Paragraph("Show this QR code at the door. Each code admits one person once.",
                new Font(Font.HELVETICA, 9, Font.ITALIC, MUTED)));
        doc.add(new Paragraph("Verify any ticket at " + qr.ticketUrl(t.getCode()),
                new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED)));
    }

    private Paragraph labeled(String label, String value) {
        Paragraph p = new Paragraph();
        p.setSpacingAfter(7);
        p.add(new Chunk(label + "\n", new Font(Font.HELVETICA, 7, Font.BOLD, MUTED)));
        p.add(new Chunk(value == null ? "—" : value, new Font(Font.HELVETICA, 12, Font.NORMAL, INK)));
        return p;
    }
}
