import XCTest
import UIKit
@testable import OpenCode

/// Read results are syntax-highlighted via highlight.js (Highlightr): the code
/// text is preserved (no markup leaks), tokens get distinct colors, and the
/// "123: " line-number gutter is kept + dimmed.
@MainActor
final class SyntaxHighlighterTests: XCTestCase {
    private func colors(_ s: NSAttributedString) -> Set<UIColor> {
        var set = Set<UIColor>()
        s.enumerateAttribute(.foregroundColor, in: NSRange(location: 0, length: s.length)) { v, _, _ in
            if let c = v as? UIColor { set.insert(c) }
        }
        return set
    }

    func testHighlightsCppWithMultipleColors() {
        let code = "int main() { return 42; }"
        let s = try? XCTUnwrap(SyntaxHighlighter.attributed(code, filename: "a.cpp", dark: true))
        let attr = try! XCTUnwrap(s)
        XCTAssertEqual(attr.string, code, "highlighting must not add or drop characters")
        XCTAssertGreaterThan(colors(attr).count, 1, "keyword/number/identifier should differ in color")
    }

    func testReadContentKeepsGutterAndCode() {
        let s = SyntaxHighlighter.readContent("745: int x = 0;\n746: return x;", filename: "a.cpp", dark: true)
        XCTAssertTrue(s.string.contains("745:"), "line-number gutter preserved")
        XCTAssertTrue(s.string.contains("746:"), "line-number gutter preserved")
        XCTAssertTrue(s.string.contains("int x = 0;"), "code preserved")
        XCTAssertTrue(s.string.contains("return x;"), "code preserved")
        XCTAssertGreaterThan(colors(s).count, 1, "code is highlighted, gutter dimmed → multiple colors")
    }

    func testLanguageMapping() {
        XCTAssertEqual(SyntaxHighlighter.language(for: "src/foo.cpp"), "cpp")
        XCTAssertEqual(SyntaxHighlighter.language(for: "a.swift"), "swift")
        XCTAssertEqual(SyntaxHighlighter.language(for: "s.py"), "python")
        XCTAssertNil(SyntaxHighlighter.language(for: "Makefile"))
    }
}
