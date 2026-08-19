package ru.example.inconsensu.registry.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.audit.application.PdnAccessLogService;
import ru.example.inconsensu.channels.domain.ChannelDecision;
import ru.example.inconsensu.channels.domain.ChannelSummaryComposer;
import ru.example.inconsensu.common.security.CurrentUser;
import ru.example.inconsensu.registry.domain.ContactAccessPolicy;
import ru.example.inconsensu.registry.domain.ContactMasker;

/**
 * Экспорт карточки клиента в PDF (UI-4, этап 8).
 *
 * <p>Документ собирается вручную на PDFBox с встроенным шрифтом DejaVu: базовые шрифты PDF не содержат
 * кириллицы, и без встраивания карточка вышла бы пустой.
 *
 * <p>Контакты маскируются по той же политике, что и на экране: выгрузка в файл не должна быть способом
 * обойти ограничение роли (NFR-3). Сам факт выгрузки попадает в журнал доступа к ПДн (FR-10.5).
 */
@Service
public class SubjectCardPdfService {

    private static final String FONT = "/fonts/DejaVuSans.ttf";
    private static final String FONT_BOLD = "/fonts/DejaVuSans-Bold.ttf";
    private static final float MARGIN = 48;
    private static final float LINE = 16;
    private static final float TITLE_SIZE = 16;
    private static final float TEXT_SIZE = 10;

    private final SubjectCardService cards;
    private final ru.example.inconsensu.catalog.application.ConsentTypeService types;
    private final PdnAccessLogService pdnAccessLog;
    private final ZoneId zone;
    private final Clock clock;

    public SubjectCardPdfService(
            SubjectCardService cards,
            ru.example.inconsensu.catalog.application.ConsentTypeService types,
            PdnAccessLogService pdnAccessLog,
            Clock clock) {
        this.cards = cards;
        this.types = types;
        this.pdnAccessLog = pdnAccessLog;
        this.zone = clock.getZone();
        this.clock = clock;
    }

    @Transactional
    public byte[] render(UUID subjectId) {
        SubjectCardService.SubjectCard card = cards.cardOf(subjectId);
        pdnAccessLog.recordSingle("/api/v1/subjects/{id}/card.pdf", subjectId);
        boolean fullContacts = ContactAccessPolicy.seesFullContacts(CurrentUser.roles());

        try (PDDocument document = new PDDocument();
                InputStream regular = getClass().getResourceAsStream(FONT);
                InputStream bold = getClass().getResourceAsStream(FONT_BOLD)) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDFont font = PDType0Font.load(document, regular);
            PDFont fontBold = PDType0Font.load(document, bold);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float y = page.getMediaBox().getHeight() - MARGIN;
                y = write(content, fontBold, TITLE_SIZE, MARGIN, y, "Карточка клиента");
                y -= LINE / 2;
                y = write(
                        content,
                        font,
                        TEXT_SIZE,
                        MARGIN,
                        y,
                        "Клиент: " + card.subject().getFullName());
                y = write(
                        content,
                        font,
                        TEXT_SIZE,
                        MARGIN,
                        y,
                        "Внешний идентификатор: " + card.subject().getExternalId());
                for (var contact : card.subject().getContacts()) {
                    String value = fullContacts
                            ? contact.getValue()
                            : ContactMasker.mask(contact.getType(), contact.getValue());
                    y = write(
                            content,
                            font,
                            TEXT_SIZE,
                            MARGIN,
                            y,
                            contact.getType().nameRu() + ": " + value);
                }

                y -= LINE / 2;
                y = write(content, fontBold, TEXT_SIZE + 2, MARGIN, y, "Каналы коммуникации");
                for (ChannelDecision decision : card.channels()) {
                    String reason = decision.allowed()
                            ? "можно"
                            : "нельзя: " + ChannelSummaryComposer.reasonText(decision.reason());
                    y = write(
                            content,
                            font,
                            TEXT_SIZE,
                            MARGIN,
                            y,
                            decision.channel().nameRu() + " — " + reason);
                }
                y = write(content, font, TEXT_SIZE, MARGIN, y, card.summaryRu());

                y -= LINE / 2;
                y = write(content, fontBold, TEXT_SIZE + 2, MARGIN, y, "Согласия");
                for (var view : card.consents()) {
                    var type = types.get(view.consent().getConsentTypeId());
                    String validUntil = view.consent().getValidUntil() == null
                            ? "бессрочно / до отзыва"
                            : DateTimeFormatter.ofPattern("dd.MM.yyyy")
                                    .format(view.consent().getValidUntil().atZone(zone));
                    y = write(
                            content,
                            font,
                            TEXT_SIZE,
                            MARGIN,
                            y,
                            "%s — %s, действует до %s".formatted(type.getNameRu(), view.statusText(), validUntil));
                }

                y -= LINE;
                write(
                        content,
                        font,
                        TEXT_SIZE - 2,
                        MARGIN,
                        y,
                        "Сформировано "
                                + DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                                        .format(clock.instant().atZone(zone)) + ", пользователь "
                                + CurrentUser.login());
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сформировать PDF карточки", e);
        }
    }

    /** Пишет строку и возвращает позицию следующей; длинные строки переносятся по словам. */
    private float write(PDPageContentStream content, PDFont font, float size, float x, float y, String text)
            throws IOException {
        float current = y;
        for (String line : wrap(text, font, size)) {
            content.beginText();
            content.setFont(font, size);
            content.newLineAtOffset(x, current);
            content.showText(line);
            content.endText();
            current -= LINE;
        }
        return current;
    }

    private List<String> wrap(String text, PDFont font, float size) throws IOException {
        float limit = PDRectangle.A4.getWidth() - 2 * MARGIN;
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (font.getStringWidth(candidate) / 1000 * size > limit && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        lines.add(current.toString());
        return lines;
    }
}
