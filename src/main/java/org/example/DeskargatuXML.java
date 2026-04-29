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
    private static final String AEMET_XML_URL = "https://www.aemet.es/xml/municipios/localidad_20019.xml";
    private static final String DEFAULT_FILE_NAME = "Xml_eraldatuta.xml";
    private static final String ORIGINAL_FILE_NAME = "originala.xml";
    private static final int MAX_DAYS = 5;
    private static final Locale BASQUE_LOCALE = Locale.forLanguageTag("eu-ES");

    public static void main(String[] args) {
        Path outputPath = resolveOutputPath(args);
        Path originalPath = resolveOriginalPath(outputPath);

        try {
            downloadAndTransformXml(originalPath, outputPath);
            System.out.println("Jatorrizko XML-a ongi deskargatu da:");
            System.out.println(originalPath.toAbsolutePath());
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

    private static Path resolveOriginalPath(Path outputPath) {
        Path parent = outputPath.getParent();
        if (parent == null) {
            return findProjectRoot().resolve(ORIGINAL_FILE_NAME);
        }

        return parent.resolve(ORIGINAL_FILE_NAME).toAbsolutePath();
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

    private static void downloadAndTransformXml(Path originalPath, Path outputPath)
            throws IOException, InterruptedException, URISyntaxException,
            ParserConfigurationException, SAXException, XPathExpressionException, TransformerException {
        Path originalParent = originalPath.getParent();
        if (originalParent != null) {
            Files.createDirectories(originalParent);
        }

        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        downloadSourceDocument(originalPath);
        Document sourceDocument = readDocumentFromFile(originalPath);
        Document transformedDocument = buildSimplifiedForecast(sourceDocument);
        writeDocument(transformedDocument, outputPath);
    }

    private static void downloadSourceDocument(Path originalPath)
            throws IOException, InterruptedException, URISyntaxException {
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

        HttpResponse<Path> response =
                client.send(request, HttpResponse.BodyHandlers.ofFile(originalPath));

        if (response.statusCode() != 200) {
            throw new IOException("HTTP erantzun okerra: " + response.statusCode());
        }
    }

    private static Document readDocumentFromFile(Path originalPath)
            throws IOException, ParserConfigurationException, SAXException {
        try (InputStream inputStream = Files.newInputStream(originalPath)) {
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
        Element root = outputDocument.createElement("iragarpena");
        root.setAttribute("udalerria", municipality);
        root.setAttribute("probintzia", province);
        root.setAttribute("sortua", generatedAt);
        outputDocument.appendChild(root);

        for (Element sourceDay : selectedDays) {
            LocalDate date = LocalDate.parse(sourceDay.getAttribute("fecha"));
            EguraldiInfo eguraldiInfo = calculateAverageWeather(sourceDay);

            Element outputDay = outputDocument.createElement("eguna");
            outputDay.setAttribute("data", date.toString());
            outputDay.setAttribute("izena", capitalize(date.getDayOfWeek()
                    .getDisplayName(TextStyle.FULL, BASQUE_LOCALE)));

            Element eguraldia = outputDocument.createElement("eguraldia");
            appendTextElement(outputDocument, eguraldia, "deskribapena", eguraldiInfo.deskribapena());
            appendTextElement(outputDocument, eguraldia, "irudia", String.valueOf(eguraldiInfo.kodea()));
            outputDay.appendChild(eguraldia);

            Element temperature = outputDocument.createElement("tenperatura");
            temperature.setAttribute("minimoa", readTemperatureValue(sourceDay, "minima"));
            temperature.setAttribute("maximoa", readTemperatureValue(sourceDay, "maxima"));
            temperature.setAttribute("batezbestekoa", String.valueOf(calculateAverageTemperature(sourceDay)));
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

    private static EguraldiInfo calculateAverageWeather(Element dayElement) {
        List<EguraldiMota> motak = getMostDetailedWeatherTypes(dayElement);
        if (motak.isEmpty()) {
            return new EguraldiInfo(14, "Hodeitsu");
        }

        double severityTotal = 0;
        double cloudinessTotal = 0;
        int cloudinessCount = 0;
        int lainoaCount = 0;
        int behelainoaCount = 0;
        int kalimaCount = 0;

        for (EguraldiMota mota : motak) {
            severityTotal += mota.severity();

            if (mota.cloudiness() >= 0) {
                cloudinessTotal += mota.cloudiness();
                cloudinessCount++;
            }

            if (mota == EguraldiMota.LAINOA) {
                lainoaCount++;
            } else if (mota == EguraldiMota.BEHELAINOA) {
                behelainoaCount++;
            } else if (mota == EguraldiMota.KALIMA) {
                kalimaCount++;
            }
        }

        int averageSeverity = (int) Math.round(severityTotal / motak.size());
        int averageCloudiness = cloudinessCount == 0
                ? 3
                : (int) Math.round(cloudinessTotal / cloudinessCount);

        if (behelainoaCount > motak.size() / 2) {
            return EguraldiMota.BEHELAINOA.toInfo();
        }
        if (kalimaCount > motak.size() / 2) {
            return EguraldiMota.KALIMA.toInfo();
        }
        if (lainoaCount > motak.size() / 2) {
            return EguraldiMota.LAINOA.toInfo();
        }

        if (averageSeverity == 1) {
            return selectCloudOnlyType(averageCloudiness, "").toInfo();
        }

        return selectAveragedType(averageSeverity, averageCloudiness).toInfo();
    }

    private static List<EguraldiMota> getMostDetailedWeatherTypes(Element dayElement) {
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

        List<EguraldiMota> motak = new ArrayList<>();
        for (Element element : candidates) {
            motak.add(classifyWeatherDescription(element.getAttribute("descripcion")));
        }
        return motak;
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

    private static EguraldiMota classifyWeatherDescription(String description) {
        String normalized = normalize(description);

        if (normalized.contains("niebla")) {
            return EguraldiMota.LAINOA;
        }
        if (normalized.contains("bruma") || normalized.contains("neblina")) {
            return EguraldiMota.BEHELAINOA;
        }
        if (normalized.contains("calima")) {
            return EguraldiMota.KALIMA;
        }

        int cloudiness = detectCloudiness(normalized);

        if (normalized.contains("tormenta") && normalized.contains("lluvia")) {
            return selectRainStormType(cloudiness);
        }
        if (normalized.contains("tormenta")) {
            return selectStormType(cloudiness);
        }
        if (normalized.contains("nieve")) {
            if (normalized.contains("escasa")) {
                return selectLightSnowType(cloudiness);
            }
            return selectSnowType(cloudiness);
        }
        if (normalized.contains("lluvia")) {
            if (normalized.contains("escasa")) {
                return selectLightRainType(cloudiness);
            }
            return selectRainType(cloudiness);
        }

        return selectCloudOnlyType(cloudiness, normalized);
    }

    private static int detectCloudiness(String normalizedDescription) {
        if (normalizedDescription.contains("cubierto")) {
            return 5;
        }
        if (normalizedDescription.contains("muy nuboso")) {
            return 4;
        }
        if (normalizedDescription.contains("intervalos nubosos")) {
            return 2;
        }
        if (normalizedDescription.contains("nuboso")) {
            return 3;
        }
        if (normalizedDescription.contains("poco nuboso")) {
            return 1;
        }
        if (normalizedDescription.contains("nubes altas")) {
            return 6;
        }
        if (normalizedDescription.contains("despejado")) {
            return 0;
        }

        return 3;
    }

    private static EguraldiMota selectAveragedType(int averageSeverity, int averageCloudiness) {
        return switch (Math.max(0, Math.min(7, averageSeverity))) {
            case 0 -> selectCloudOnlyType(averageCloudiness, "");
            case 1 -> EguraldiMota.LAINOA;
            case 2 -> selectLightRainType(averageCloudiness);
            case 3 -> selectRainType(averageCloudiness);
            case 4 -> selectStormType(averageCloudiness);
            case 5 -> selectRainStormType(averageCloudiness);
            case 6 -> selectLightSnowType(averageCloudiness);
            case 7 -> selectSnowType(averageCloudiness);
            default -> EguraldiMota.HODEITSU;
        };
    }

    private static EguraldiMota selectCloudOnlyType(int cloudiness, String normalizedDescription) {
        int normalizedCloudiness = Math.max(0, Math.min(6, cloudiness));
        if (normalizedDescription.contains("nubes altas")) {
            return EguraldiMota.HODEI_ALTUAK;
        }

        return switch (normalizedCloudiness) {
            case 0 -> EguraldiMota.OSKARBI;
            case 1 -> EguraldiMota.HODEI_GUTXI;
            case 2 -> EguraldiMota.HODEI_TARTEAK;
            case 3 -> EguraldiMota.HODEITSU;
            case 4 -> EguraldiMota.OSO_HODEITSU;
            case 5 -> EguraldiMota.ESTALIA;
            case 6 -> EguraldiMota.HODEI_ALTUAK;
            default -> EguraldiMota.HODEITSU;
        };
    }

    private static EguraldiMota selectLightRainType(int cloudiness) {
        return switch (normalizePrecipitationCloudiness(cloudiness)) {
            case 2 -> EguraldiMota.HODEI_TARTEAK_EURI_TXIKIA;
            case 3 -> EguraldiMota.HODEITSU_EURI_TXIKIAREKIN;
            case 4 -> EguraldiMota.OSO_HODEITSU_EURI_TXIKIAREKIN;
            case 5 -> EguraldiMota.ESTALIA_EURI_TXIKIAREKIN;
            default -> EguraldiMota.HODEITSU_EURI_TXIKIAREKIN;
        };
    }

    private static EguraldiMota selectRainType(int cloudiness) {
        return switch (normalizePrecipitationCloudiness(cloudiness)) {
            case 2 -> EguraldiMota.HODEI_TARTEAK_EURIAREKIN;
            case 3 -> EguraldiMota.HODEITSU_EURIAREKIN;
            case 4 -> EguraldiMota.OSO_HODEITSU_EURIAREKIN;
            case 5 -> EguraldiMota.ESTALIA_EURIAREKIN;
            default -> EguraldiMota.HODEITSU_EURIAREKIN;
        };
    }

    private static EguraldiMota selectStormType(int cloudiness) {
        return switch (normalizePrecipitationCloudiness(cloudiness)) {
            case 2 -> EguraldiMota.HODEI_TARTEAK_EKAITZAREKIN;
            case 3 -> EguraldiMota.HODEITSU_EKAITZAREKIN;
            case 4 -> EguraldiMota.OSO_HODEITSU_EKAITZAREKIN;
            case 5 -> EguraldiMota.ESTALIA_EKAITZAREKIN;
            default -> EguraldiMota.HODEITSU_EKAITZAREKIN;
        };
    }

    private static EguraldiMota selectRainStormType(int cloudiness) {
        return switch (normalizePrecipitationCloudiness(cloudiness)) {
            case 2 -> EguraldiMota.HODEI_TARTEAK_EURIA_EKAITZAREKIN;
            case 3 -> EguraldiMota.HODEITSU_EURIA_EKAITZAREKIN;
            case 4 -> EguraldiMota.OSO_HODEITSU_EURIA_EKAITZAREKIN;
            case 5 -> EguraldiMota.ESTALIA_EURIA_EKAITZAREKIN;
            default -> EguraldiMota.HODEITSU_EURIA_EKAITZAREKIN;
        };
    }

    private static EguraldiMota selectLightSnowType(int cloudiness) {
        return switch (normalizePrecipitationCloudiness(cloudiness)) {
            case 2 -> EguraldiMota.HODEI_TARTEAK_ELUR_TXIKIA;
            case 3 -> EguraldiMota.HODEITSU_ELUR_TXIKIAREKIN;
            case 4 -> EguraldiMota.OSO_HODEITSU_ELUR_TXIKIAREKIN;
            case 5 -> EguraldiMota.ESTALIA_ELUR_TXIKIAREKIN;
            default -> EguraldiMota.HODEITSU_ELUR_TXIKIAREKIN;
        };
    }

    private static EguraldiMota selectSnowType(int cloudiness) {
        return switch (normalizePrecipitationCloudiness(cloudiness)) {
            case 2 -> EguraldiMota.HODEI_TARTEAK_ELURRAREKIN;
            case 3 -> EguraldiMota.HODEITSU_ELURRAREKIN;
            case 4 -> EguraldiMota.OSO_HODEITSU_ELURRAREKIN;
            case 5 -> EguraldiMota.ESTALIA_ELURRAREKIN;
            default -> EguraldiMota.HODEITSU_ELURRAREKIN;
        };
    }

    private static int normalizePrecipitationCloudiness(int cloudiness) {
        return Math.max(2, Math.min(5, cloudiness));
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
        return Normalizer.normalize(text.toLowerCase(BASQUE_LOCALE), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim();
    }

    private static String capitalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        return text.substring(0, 1).toUpperCase(BASQUE_LOCALE) + text.substring(1);
    }

    private record EguraldiInfo(int kodea, String deskribapena) {
    }

    private enum EguraldiMota {
        OSKARBI(11, "Oskarbi", 0, 0),
        HODEI_GUTXI(12, "Hodei gutxi", 0, 1),
        HODEI_TARTEAK(13, "Hodei-tarteak", 0, 2),
        HODEITSU(14, "Hodeitsu", 0, 3),
        OSO_HODEITSU(15, "Oso hodeitsu", 0, 4),
        ESTALIA(16, "Estalia", 0, 5),
        HODEI_ALTUAK(17, "Hodei altuak", 0, 6),

        HODEI_TARTEAK_EURI_TXIKIA(23, "Hodei-tarteak eta euri txikia", 2, 2),
        HODEITSU_EURI_TXIKIAREKIN(24, "Hodeitsu, euri txikiarekin", 2, 3),
        OSO_HODEITSU_EURI_TXIKIAREKIN(25, "Oso hodeitsu, euri txikiarekin", 2, 4),
        ESTALIA_EURI_TXIKIAREKIN(26, "Estalia, euri txikiarekin", 2, 5),

        HODEI_TARTEAK_ELUR_TXIKIA(33, "Hodei-tarteak eta elur txikia", 6, 2),
        HODEITSU_ELUR_TXIKIAREKIN(34, "Hodeitsu, elur txikiarekin", 6, 3),
        OSO_HODEITSU_ELUR_TXIKIAREKIN(35, "Oso hodeitsu, elur txikiarekin", 6, 4),
        ESTALIA_ELUR_TXIKIAREKIN(36, "Estalia, elur txikiarekin", 6, 5),

        HODEI_TARTEAK_EURIAREKIN(43, "Hodei-tarteak eta euria", 3, 2),
        HODEITSU_EURIAREKIN(44, "Hodeitsu, euriarekin", 3, 3),
        OSO_HODEITSU_EURIAREKIN(45, "Oso hodeitsu, euriarekin", 3, 4),
        ESTALIA_EURIAREKIN(46, "Estalia, euriarekin", 3, 5),

        HODEI_TARTEAK_EKAITZAREKIN(51, "Hodei-tarteak eta ekaitza", 4, 2),
        HODEITSU_EKAITZAREKIN(52, "Hodeitsu, ekaitzarekin", 4, 3),
        OSO_HODEITSU_EKAITZAREKIN(53, "Oso hodeitsu, ekaitzarekin", 4, 4),
        ESTALIA_EKAITZAREKIN(54, "Estalia, ekaitzarekin", 4, 5),

        HODEI_TARTEAK_ELURRAREKIN(63, "Hodei-tarteak eta elurra", 7, 2),
        HODEITSU_ELURRAREKIN(64, "Hodeitsu, elurrarekin", 7, 3),
        OSO_HODEITSU_ELURRAREKIN(65, "Oso hodeitsu, elurrarekin", 7, 4),
        ESTALIA_ELURRAREKIN(66, "Estalia, elurrarekin", 7, 5),

        HODEI_TARTEAK_EURIA_EKAITZAREKIN(71, "Hodei-tarteak, euria eta ekaitza", 5, 2),
        HODEITSU_EURIA_EKAITZAREKIN(72, "Hodeitsu, euria eta ekaitzarekin", 5, 3),
        OSO_HODEITSU_EURIA_EKAITZAREKIN(73, "Oso hodeitsu, euria eta ekaitzarekin", 5, 4),
        ESTALIA_EURIA_EKAITZAREKIN(74, "Estalia, euria eta ekaitzarekin", 5, 5),

        LAINOA(81, "Lainoa", 1, -1),
        BEHELAINOA(82, "Behelainoa", 1, -1),
        KALIMA(83, "Kalima", 1, -1);

        private final int kodea;
        private final String deskribapena;
        private final int severity;
        private final int cloudiness;

        EguraldiMota(int kodea, String deskribapena, int severity, int cloudiness) {
            this.kodea = kodea;
            this.deskribapena = deskribapena;
            this.severity = severity;
            this.cloudiness = cloudiness;
        }

        public int severity() {
            return severity;
        }

        public int cloudiness() {
            return cloudiness;
        }

        public EguraldiInfo toInfo() {
            return new EguraldiInfo(kodea, deskribapena);
        }
    }
}
