"""Convert an immutable vMAJOR.MINOR.PATCH tag to Android version metadata."""
import argparse
import re


def release_version(tag):
    match = re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", tag)
    if not match:
        raise ValueError("Use a version tag such as v1.2.0")
    major, minor, patch = map(int, match.groups())
    code = major * 1_000_000 + minor * 1_000 + patch
    if minor > 999 or patch > 999 or not 0 < code <= 2_100_000_000:
        raise ValueError("Version is outside the supported Android version range")
    return tag[1:], code


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    args = parser.parse_args()
    try:
        name, code = release_version(args.tag)
    except ValueError as error:
        parser.error(str(error))
    print(f"name={name}")
    print(f"code={code}")
