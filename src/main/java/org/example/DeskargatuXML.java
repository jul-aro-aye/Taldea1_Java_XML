package org.example;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DeskargatuXML {
    private static final String AEMET_XML_URL = "https://www.aemet.es/xml/municipios/localidad_20076.xml";
    private static final String DEFAULT_FILE_NAME = "Xml_eraldatuta.xml";
    private static final int MAX_DAYS = 5;
    private static final Locale SPANISH_LOCALE = Locale.forLanguageTag("es-ES");

    public static void main(String[] args) {
        Path outputPath = resolveOutputPath(args);

        try {
            downloadAndTransformXml(outputPath);
            System.out.println("XML eraldaketa ongi sortu da:");
            System.out.println(outputPath.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("Akatsa XML fitxategia deskargatu edo eraldatzean.");
            System.err.println(e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }
    }

    private static Path resolveOutputPath(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            return Path.of(args[0]).toAbsolutePath();
        }

        return findProjectRoot().resolve(DEFAULT_FILE_NAME);
    }

    private static Path findProjectRoot() {
        try {
            Path codeSource = Path.of(DeskargatuXML.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());

            Path current = Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();

            while (current != null) {
                if (Files.exists(current.resolve("pom.xml"))) {
                    return current;
                }
                current = current.getParent();
            }
        } catch (Exception ignored) {
            // If the runtime location cannot be resolved, we fall back to the current directory.
        }

        return Path.of("").toAbsolutePath();
    }

    private static void downloadAndTransformXml(Path outputPath)
            throws IOException, InterruptedException, URISyntaxException,
            ParserConfigurationException, SAXException, XPathExpressionException, TransformerException {
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Document sourceDocument = downloadSourceDocument();
        Document transformedDocument = buildSimplifiedForecast(sourceDocument);
        writeDocument(transformedDocument, outputPath);
    }

    private static Document downloadSourceDocument()
            throws IOException, InterruptedException, URISyntaxException,
            ParserConfigurationException, SAXException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(AEMET_XML_URL))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Taldea1_Java_XML/1.0")
                .GET()
                .build();

        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP erantzun okerra: " + response.statusCode());
        }

        try (InputStream inputStream = response.body()) {
            DocumentBuilder builder = createDocumentBuilder();
            return builder.parse(inputStream);
        }
    }

    private static Document buildSimplifiedForecast(Document sourceDocument)
            throws ParserConfigurationException, XPathExpressionException {
        XPath xPath = XPathFactory.newInstance().newXPath();

        String municipality = xPath.evaluate("/root/nombre", sourceDocument).trim();
        String province = xPath.evaluate("/root/provincia", sourceDocument).trim();
        String generatedAt = xPath.evaluate("/root/elaborado", sourceDocument).trim();

        List<Element> selectedDays = selectNextDays(sourceDocument, xPath);
        if (selectedDays.isEmpty()) {
            throw new IllegalStateException("Ez da iragarpenik aurkitu XML-an.");
        }

        Document outputDocument = createDocumentBuilder().newDocument();
        Element root = outputDocument.createElement("prevision");
        root.setAttribute("municipio", municipality);
        root.setAttribute("provincia", province);
        root.setAttribute("generado", generatedAt);
        outputDocument.appendChild(root);

        for (Element sourceDay : selectedDays) {
            LocalDate date = LocalDate.parse(sourceDay.getAttribute("fecha"));
            Element outputDay = outputDocument.createElement("dia");
            outputDay.setAttribute("fecha", date.toString());
            outputDay.setAttribute("nombre", capitalize(date.getDayOfWeek()
                    .getDisplayName(TextStyle.FULL, SPANISH_LOCALE)));

            appendTextElement(outputDocument, outputDay, "estado_promedio", calculateAverageWeather(sourceDay));

            Element temperature = outputDocument.createElement("temperatura");
            temperature.setAttribute("minima", readTemperatureValue(sourceDay, "minima"));
            temperature.setAttribute("maxima", readTemperatureValue(sourceDay, "maxima"));
            temperature.setAttribute("media", String.valueOf(calculateAverageTemperature(sourceDay)));
            outputDay.appendChild(temperature);

            root.appendChild(outputDay);
        }

        return outputDocument;
    }

    private static List<Element> selectNextDays(Document sourceDocument, XPath xPath)
            throws XPathExpressionException {
        NodeList dayNodes = (NodeList) xPath.evaluate("/root/prediccion/dia", sourceDocument, XPathConstants.NODESET);
        List<Element> futureDays = new ArrayList<>();
        List<Element> fallbackDays = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (int i = 0; i < dayNodes.getLength(); i++) {
            Node node = dayNodes.item(i);
            if (!(node instanceof Element dayElement)) {
                continue;
            }

            fallbackDays.add(dayElement);

            String dateText = dayElement.getAttribute("fecha");
            if (dateText == null || dateText.isBlank()) {
                continue;
            }

            LocalDate dayDate = LocalDate.parse(dateText);
            if (!dayDate.isBefore(today)) {
                futureDays.add(dayElement);
            }
        }

        List<Element> source = futureDays.isEmpty() ? fallbackDays : futureDays;
        int endIndex = Math.min(MAX_DAYS, source.size());
        return new ArrayList<>(source.subList(0, endIndex));
    }

    private static String calculateAverageWeather(Element dayElement) {
        List<String> descriptions = getMostDetailedWeatherDescriptions(dayElement);
        if (descriptions.isEmpty()) {
            return "sin datos";
        }

        double total = 0;
        for (String description : descriptions) {
            total += mapWeatherDescriptionToLevel(description);
        }

        int averageLevel = (int) Math.round(total / descriptions.size());
        return mapLevelToWeatherLabel(averageLevel);
    }

    private static List<String> getMostDetailedWeatherDescriptions(Element dayElement) {
        NodeList weatherNodes = dayElement.getElementsByTagName("estado_cielo");
        List<Element> candidates = new ArrayList<>();
        int bestPeriodHours = Integer.MAX_VALUE;

        for (int i = 0; i < weatherNodes.getLength(); i++) {
            Node node = weatherNodes.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }

            String description = element.getAttribute("descripcion").trim();
            if (description.isBlank()) {
                continue;
            }

            int periodHours = parsePeriodHours(element.getAttribute("periodo"));
            if (periodHours < bestPeriodHours) {
                bestPeriodHours = periodHours;
                candidates.clear();
            }

            if (periodHours == bestPeriodHours) {
                candidates.add(element);
            }
        }

        List<String> descriptions = new ArrayList<>();
        for (Element element : candidates) {
            descriptions.add(element.getAttribute("descripcion").trim());
        }
        return descriptions;
    }

    private static int parsePeriodHours(String period) {
        if (period == null || period.isBlank() || !period.contains("-")) {
            return Integer.MAX_VALUE;
        }

        String[] parts = period.split("-");
        if (parts.length != 2) {
            return Integer.MAX_VALUE;
        }

        try {
            int start = Integer.parseInt(parts[0]);
            int end = Integer.parseInt(parts[1]);
            int hours = end - start;
            return hours > 0 ? hours : Integer.MAX_VALUE;
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static int mapWeatherDescriptionToLevel(String description) {
        String normalized = normalize(description);

        if (normalized.contains("tormenta")
                || normalized.contains("lluvia")
                || normalized.contains("nieve")
                || normalized.contains("granizo")) {
            return 4;
        }
        if (normalized.contains("muy nuboso") || normalized.contains("cubierto")) {
            return 3;
        }
        if (normalized.contains("niebla")
                || normalized.contains("nuboso")
                || normalized.contains("intervalos nubosos")) {
            return 2;
        }
        if (normalized.contains("poco nuboso")) {
            return 1;
        }
        if (normalized.contains("despejado")) {
            return 0;
        }

        return 2;
    }

    private static String mapLevelToWeatherLabel(int level) {
        return switch (Math.max(0, Math.min(4, level))) {
            case 0 -> "despejado";
            case 1 -> "poco nuboso";
            case 2 -> "nublado";
            case 3 -> "muy nuboso";
            case 4 -> "lluvioso";
            default -> "sin datos";
        };
    }

    private static int calculateAverageTemperature(Element dayElement) {
        NodeList temperatureData = dayElement.getElementsByTagName("dato");
        int total = 0;
        int count = 0;

        for (int i = 0; i < temperatureData.getLength(); i++) {
            Node node = temperatureData.item(i);
            if (!(node.getParentNode() instanceof Element parent)) {
                continue;
            }

            if (!"temperatura".equals(parent.getTagName())) {
                continue;
            }

            String value = node.getTextContent().trim();
            if (value.isBlank()) {
                continue;
            }

            total += Integer.parseInt(value);
            count++;
        }

        if (count > 0) {
            return Math.round((float) total / count);
        }

        int min = Integer.parseInt(readTemperatureValue(dayElement, "minima"));
        int max = Integer.parseInt(readTemperatureValue(dayElement, "maxima"));
        return Math.round((min + max) / 2.0f);
    }

    private static String readTemperatureValue(Element dayElement, String tagName) {
        NodeList temperatureNodes = dayElement.getElementsByTagName("temperatura");
        if (temperatureNodes.getLength() == 0) {
            return "";
        }

        Node temperatureNode = temperatureNodes.item(0);
        if (!(temperatureNode instanceof Element temperatureElement)) {
            return "";
        }

        NodeList values = temperatureElement.getElementsByTagName(tagName);
        if (values.getLength() == 0) {
            return "";
        }

        return values.item(0).getTextContent().trim();
    }

    private static void writeDocument(Document document, Path outputPath)
            throws TransformerException, IOException {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

        try (var writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            transformer.transform(new DOMSource(document), new StreamResult(writer));
        }
    }

    private static DocumentBuilder createDocumentBuilder()
            throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder();
    }

    private static void appendTextElement(Document document, Element parent, String tagName, String value) {
        Element child = document.createElement(tagName);
        child.setTextContent(value);
        parent.appendChild(child);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text.toLowerCase(SPANISH_LOCALE), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim();
    }

    private static String capitalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        return text.substring(0, 1).toUpperCase(SPANISH_LOCALE) + text.substring(1);
    }
}
