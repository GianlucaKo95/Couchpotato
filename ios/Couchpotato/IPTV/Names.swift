import Foundation

/// Display clean-up for provider names, like on the TVs: "DE: Das Erste", "|DE| Das Erste",
/// "[DE] Das Erste", "DE - HD - Das Erste" → "Das Erste". Only known tags are removed.
enum Names {
    private static let tags =
        "DE|GER|DEU|GERMAN|AT|CH|EN|ENG|UK|US|USA|FR|IT|ES|NL|PL|TR|RU|AR|EX|EU|MULTI|DUAL|" +
        "4K|UHD|FHD|HD|SD|HEVC|NF|NETFLIX|AMZ|AMAZON|PRIME|DSNP|DISNEY|SKY|ATV|APPLE|HBO|MAX|VOD|SERIE|SERIES|FILM|MOVIE|KIDS"

    private static let leading = try! NSRegularExpression(
        pattern: #"^\s*(?:\|[^|]{1,14}\||\[[^\]]{1,14}]|\((?:\#(tags))\)|(?:\#(tags))(?:\s*[:|]\s*|\s+-\s+|\s+(?=\|)))"#
    )
    private static let separators = CharacterSet(charactersIn: " -:|·–—_")

    static func display(_ raw: String) -> String {
        var s = raw
        while true {
            let ns = s as NSString
            guard let m = leading.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)),
                  m.range.location + m.range.length < ns.length else { break }   // never strip everything
            s = ns.substring(from: m.range.location + m.range.length)
        }
        let out = s.trimmingCharacters(in: separators)
        return out.isEmpty ? raw.trimmed : out
    }
}
