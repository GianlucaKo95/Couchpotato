import XCTest
@testable import Couchpotato

/// The iPhone must read and write exactly what the TVs (CloudSync.kt) do.
final class HouseholdTests: XCTestCase {
    // made like on the TVs: PBKDF2-SHA256 210 000 / gzip / AES-256-GCM with SHA-256(auth) as AAD
    let code = "7KQ2MX9P4T"
    let authHex = "77aba12f21861a8389581737da736ae93649154df234345422b724feec6c7961"
    let blob = "AQABAgMEBQYHCAkKC2qASesEniRlHeRe59cbeTGPsV6xFMKGJM3tQA1GqqgFne9qt6pn0Qgw5Z/bgW/AIIU2dimxPwjeBEheqRSCt/lO+tfaWBgrR2Cz7xQMh0WVujNp/U9SIe6nqcnzl9+K4jWOqHMeGbhMpt6ijGwrUsWbXuDhrvQTZp3c9nHDCt/S8OezgS3JhKY/gekLaNYfHDvZ/sXkEWCeAluHpFIpOdsuvwsdclFESzxUICla2H2nDuW6xC+lMKTup0MgADrPwP2vQQYAOR2egK2Q1q8V7qd5t3EHL5jaycOXnnXwZNkQEt+Xrd5D00PEb2OJrQiGQ0MsO+mqgbNDA5/U"

    func testKeysMatchTheTvs() {
        XCTAssertEqual(HouseholdKeys.derive(code: code).authHex, authHex)
    }

    func testOpensBlobFromTv() throws {
        let keys = HouseholdKeys.derive(code: code)
        let data = try XCTUnwrap(keys.open(blob))
        let snap = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let files = HouseholdData.filesOf(snap)
        XCTAssertNotNil(files["couchpotato"]?["liveFavs"])
        XCTAssertEqual((snap["iptv"] as? [String: Any])?["user"] as? String, "u")
    }

    func testSealRoundTrip() throws {
        let keys = HouseholdKeys.derive(code: code)
        let json = Data(#"{"version":1,"preferences":{"a":{"b":{"type":"string","value":"ü"}}}}"#.utf8)
        let sealed = try keys.seal(json)
        XCTAssertEqual(Data(base64Encoded: sealed)?.first, 1)
        XCTAssertEqual(keys.open(sealed), json)
        XCTAssertNil(HouseholdKeys.derive(code: "0000000000").open(sealed))
    }

    func testGzipHeaderIsPlainGzip() throws {
        let z = try Gzip.compress(Data("hallo hallo hallo".utf8))
        XCTAssertEqual(Array(z.prefix(4)), [0x1f, 0x8b, 8, 0])
        XCTAssertEqual(Gzip.decompress(z), Data("hallo hallo hallo".utf8))
    }

    func testCodeNormalising() {
        XCTAssertEqual(HouseholdCode.normalize("7kq2m-x9p4t"), "7KQ2MX9P4T")
        XCTAssertEqual(HouseholdCode.normalize("o1l-I"), "0111")
    }

    func testMergeKeepsLocalChangeAndRemoteRest() {
        func s(_ v: String) -> [String: Any] { ["type": "string", "value": v] }
        let base: [String: Any] = ["preferences": ["f": ["a": s("1"), "b": s("1")]]]
        let local: [String: Any] = ["preferences": ["f": ["a": s("2"), "b": s("1")]]]
        let remote: [String: Any] = ["preferences": ["f": ["a": s("1"), "b": s("3"), "c": s("9")]]]
        let merged = HouseholdData.filesOf(SnapshotMerge.merge(base: base, local: local, remote: remote))["f"]!
        XCTAssertEqual((merged["a"] as? [String: Any])?["value"] as? String, "2")
        XCTAssertEqual((merged["b"] as? [String: Any])?["value"] as? String, "3")
        XCTAssertEqual((merged["c"] as? [String: Any])?["value"] as? String, "9")
    }

    func testLinkParsing() {
        let a = XtreamLogin.fromLink("http://tv.example:8080/get.php?username=max&password=geheim&type=m3u_plus")
        XCTAssertEqual(a?.base, "http://tv.example:8080")
        XCTAssertEqual(a?.user, "max")
        let b = XtreamLogin.fromLink("tv.example:8080/live/max/geheim/123.ts")
        XCTAssertEqual(b?.password, "geheim")
    }

    func testNames() {
        XCTAssertEqual(Names.display("DE: Das Erste HD"), "Das Erste HD")
        XCTAssertEqual(Names.display("|DE| ZDF"), "ZDF")
        XCTAssertEqual(Names.display("CSI: Miami"), "CSI: Miami")
    }
}
