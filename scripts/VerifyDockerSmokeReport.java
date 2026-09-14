import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/** JDK-only, source-launch verification of the opt-in Docker smoke's Surefire report. */
public final class VerifyDockerSmokeReport {
    private static final String TEST_CLASS = "com.soklet.toystore.mcp.ToyStoreMcpDockerSmokeTests";
    private static final String TEST_METHOD =
            "publishedMcpPortSupportsAuthenticatedDiscoveryAndInvocationWithHostValidation";
    private static final int MAX_REPORT_BYTES = 1024 * 1024;

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            selfTest();
            return;
        }
        require(args.length == 1 && !args[0].startsWith("--"),
                "Usage: java scripts/VerifyDockerSmokeReport.java REPORT.xml | --self-test");
        Path report = Path.of(args[0]);
        require(Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS),
                "Expected a regular, non-symlink Docker smoke report");
        require(Files.size(report) <= MAX_REPORT_BYTES, "Docker smoke report exceeds 1 MiB");
        byte[] bytes;
        try (var input = Files.newInputStream(report)) {
            bytes = input.readNBytes(MAX_REPORT_BYTES + 1);
        }
        verify(bytes);
        System.out.println("PASS Docker smoke report: 1 test, 0 skipped, 0 failures, 0 errors");
    }

    private static void verify(byte[] bytes) throws Exception {
        require(bytes.length > 0 && bytes.length <= MAX_REPORT_BYTES,
                "Expected a nonempty Docker smoke report of at most 1 MiB");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setAttribute("jdk.xml.maxElementDepth", "64");
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new ErrorHandler() {
            public void warning(SAXParseException error) throws SAXParseException { throw error; }
            public void error(SAXParseException error) throws SAXParseException { throw error; }
            public void fatalError(SAXParseException error) throws SAXParseException { throw error; }
        });
        var document = builder.parse(new ByteArrayInputStream(bytes));
        Element suite = document.getDocumentElement();
        require(suite.getTagName().equals("testsuite") && suite.getNamespaceURI() == null,
                "Expected one unnamespaced Surefire testsuite");
        require(suite.getAttribute("name").equals(TEST_CLASS), "Wrong Docker smoke suite");
        require(suite.getAttribute("tests").equals("1"), "Expected exactly one reported test");
        for (String count : List.of("skipped", "failures", "errors"))
            require(suite.getAttribute(count).equals("0"), "Docker smoke must report zero " + count);

        var cases = suite.getElementsByTagNameNS("*", "testcase");
        require(cases.getLength() == 1, "Expected exactly one actual testcase");
        Element test = (Element) cases.item(0);
        require(test.getParentNode() == suite && test.getNamespaceURI() == null,
                "Expected a direct, unnamespaced testcase");
        require(test.getAttribute("classname").equals(TEST_CLASS), "Wrong testcase class");
        require(test.getAttribute("name").equals(TEST_METHOD), "Wrong testcase method");
        for (String outcome : List.of("skipped", "failure", "error"))
            require(suite.getElementsByTagNameNS("*", outcome).getLength() == 0,
                    "Docker smoke contains a " + outcome + " outcome");
        require(suite.getElementsByTagNameNS("*", "testsuite").getLength() == 0,
                "Nested suites are not accepted");
        for (Node child = test.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element)
                require(element.getNamespaceURI() == null
                                && List.of("system-out", "system-err").contains(element.getTagName()),
                        "Unexpected testcase outcome element");
        }
    }

    private static void selfTest() throws Exception {
        String testcase = "<testcase classname=\"" + TEST_CLASS + "\" name=\"" + TEST_METHOD + "\"/>";
        String valid = "<testsuite name=\"" + TEST_CLASS
                + "\" tests=\"1\" skipped=\"0\" failures=\"0\" errors=\"0\">" + testcase + "</testsuite>";
        verify(valid.getBytes(StandardCharsets.UTF_8));
        int count = 1;
        List<String> invalid = List.of(
                "", "<testsuite>",
                valid.replace("tests=\"1\"", "tests=\"0\""),
                valid.replace("skipped=\"0\"", "skipped=\"1\""),
                valid.replace("failures=\"0\"", "failures=\"1\""),
                valid.replace("errors=\"0\"", "errors=\"1\""),
                valid.replace(" skipped=\"0\"", ""),
                valid.replace("tests=\"1\"", "tests=\"01\""),
                valid.replace("name=\"" + TEST_CLASS, "name=\"WrongSuite"),
                valid.replace("classname=\"" + TEST_CLASS, "classname=\"WrongClass"),
                valid.replace(TEST_METHOD, "wrongMethod"),
                valid.replace(testcase, ""),
                valid.replace(testcase, testcase + testcase),
                valid.replace(testcase, "<nested>" + testcase + "</nested>"),
                valid.replace("<testcase ", "<testcase xmlns=\"urn:wrong\" "),
                valid.replace("<testsuite ", "<testsuite xmlns=\"urn:wrong\" "),
                valid.replace("/>", "><skipped/></testcase>"),
                valid.replace("/>", "><failure/></testcase>"),
                valid.replace("/>", "><error/></testcase>"),
                valid.replace("/>", "><unknown-outcome/></testcase>"),
                valid.replace("/>", "><x:skipped xmlns:x=\"urn:wrong\"/></testcase>"),
                valid.replace(testcase, testcase + "<testsuite/>"),
                "<!DOCTYPE testsuite [<!ENTITY x SYSTEM 'file:///not-to-be-read'>]>" + valid,
                "<!DOCTYPE testsuite SYSTEM 'https://example.invalid/not-to-be-fetched'>" + valid,
                valid.replace(testcase, "<nested>".repeat(65) + testcase + "</nested>".repeat(65)));
        for (String input : invalid) {
            boolean rejected = false;
            try { verify(input.getBytes(StandardCharsets.UTF_8)); }
            catch (Exception expected) { rejected = true; }
            require(rejected, "Self-test accepted an invalid Docker smoke report");
            count++;
        }
        boolean oversizedRejected = false;
        try { verify(new byte[MAX_REPORT_BYTES + 1]); }
        catch (IllegalArgumentException expected) { oversizedRejected = true; }
        require(oversizedRejected, "Self-test accepted an oversized report");
        System.out.println("Docker smoke report self-test passed (" + (count + 1) + " cases)");
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalArgumentException(message);
    }
}
