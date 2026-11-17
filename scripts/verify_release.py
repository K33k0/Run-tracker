"""Reject unsigned, debug, incorrectly versioned or incorrectly signed release APKs."""
import argparse
import pathlib
import re
import subprocess


def verify_metadata(badging, signature, expected_name, expected_code, expected_certificate):
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.MULTILINE)
    if not package or package.groups() != ("com.runzone", str(expected_code), expected_name):
        raise ValueError("APK package or version does not match the release tag")
    if re.search(r"^application-debuggable\s*$", badging, re.MULTILINE):
        raise ValueError("Refusing to publish a debuggable APK")
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$", signature, re.MULTILINE)
    if len(certificates) != 1 or certificates[0].lower() != expected_certificate.strip().lower():
        raise ValueError("APK does not match the pinned release signing certificate")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=pathlib.Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--code", required=True, type=int)
    parser.add_argument("--certificate", required=True, type=pathlib.Path)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--aapt", required=True)
    args = parser.parse_args()
    try:
        signature = subprocess.check_output([args.apksigner, "verify", "--verbose", "--print-certs", str(args.apk)], text=True)
        badging = subprocess.check_output([args.aapt, "dump", "badging", str(args.apk)], text=True)
        verify_metadata(badging, signature, args.name, args.code, args.certificate.read_text(encoding="utf-8"))
    except (ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Release verification failed: {error}\n")
    print(f"Verified production APK: {args.name} ({args.code}), pinned signing certificate")
