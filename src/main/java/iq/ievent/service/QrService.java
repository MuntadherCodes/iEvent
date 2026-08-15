package iq.ievent.service;

import io.nayuki.qrcodegen.QrCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class QrService {

    private final String baseUrl;

    public QrService(@Value("${app.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public String ticketUrl(String ticketCode) {
        return baseUrl + "/t/" + ticketCode;
    }

    /** Renders the ticket QR as a standalone SVG string (dark modules on transparent). */
    public String ticketQrSvg(String ticketCode) {
        QrCode qr = QrCode.encodeText(ticketUrl(ticketCode), QrCode.Ecc.MEDIUM);
        int size = qr.size;
        int border = 2;
        int dim = size + border * 2;
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (qr.getModule(x, y)) {
                    path.append("M").append(x + border).append(",").append(y + border).append("h1v1h-1z");
                }
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + dim + " " + dim
                + "\" shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"Ticket QR code\">"
                + "<path d=\"" + path + "\" fill=\"#23222f\"/></svg>";
    }
}
