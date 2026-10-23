#!/usr/bin/env python3
"""Offline deployment prerequisite check. Never installs or changes a CA store."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import ssl
import subprocess
import sys

# certifi GHSA-6q48-6c7w-9gw2. The official 2026.10.19 bundle retains other
# emSign roots; match this removed root by DER fingerprint, never by its alias.
REMOVED_ROOT = "86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b"
CERTIFI_MINIMUM = (2026, 10, 19)


def validate_der_certificates(certificates):
    if not certificates:
        raise ValueError("CA store is empty or could not be inspected")
    if any(hashlib.sha256(der).hexdigest() == REMOVED_ROOT for der in certificates):
        raise ValueError("removed emSign ECC G3 root is still trusted")
    return len(certificates)


def validate_pem(text):
    certificates = re.findall(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", text, re.S)
    return validate_der_certificates([ssl.PEM_cert_to_DER_cert(pem) for pem in certificates])


def validate_certifi_version(version):
    if not re.fullmatch(r"\d+\.\d+\.\d+", version) or tuple(map(int, version.split("."))) < CERTIFI_MINIMUM:
        raise ValueError("certifi 2026.10.19 or newer is required in this application environment")


def validate_ca_directory(directory):
    count = 0
    if directory:
        for path in Path(directory).iterdir():
            if path.is_file():
                data = path.read_bytes()
                # Also catch an exact DER file; never rely on filename or alias.
                validate_der_certificates([data])
                if b"-----BEGIN CERTIFICATE-----" in data:
                    count += validate_pem(data.decode("ascii"))
    return count


def check_python():
    import certifi
    validate_certifi_version(certifi.__version__)
    if os.environ.get("PYTHONHTTPSVERIFY") == "0":
        raise ValueError("Python TLS verification is disabled")
    if os.environ.get("SSL_CERT_DIR"):
        # OpenSSL lazily loads directory certificates: get_ca_certs cannot prove
        # their absence. Require a reviewed bundle instead of reporting a pass.
        raise ValueError("SSL_CERT_DIR requires separate CA-directory review")
    contexts = [ssl.create_default_context(cafile=certifi.where()), ssl.create_default_context()]
    for variable in ("REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE", "SSL_CERT_FILE"):
        if os.environ.get(variable):
            contexts.append(ssl.create_default_context(cafile=os.environ[variable]))
    counts = [validate_der_certificates(context.get_ca_certs(binary_form=True)) for context in contexts]
    # Hashed-directory CAs load lazily and are absent from get_ca_certs until
    # used. Inspect the default directory as well as the loaded SSLContexts.
    directory_count = validate_ca_directory(ssl.get_default_verify_paths().capath)
    return {"certifiVersion": certifi.__version__, "checkedStores": len(counts), "checkedCertificates": counts,
            "checkedDirectoryCertificates": directory_count}


def validate_java_options(environment):
    overrides = {"java.home", "java.security.properties", "javax.net.ssl.trustStore",
                 "javax.net.ssl.trustStoreType", "javax.net.ssl.trustStoreProvider"}
    for variable in ("JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        try:
            options = shlex.split(environment.get(variable, ""))
        except ValueError:
            raise ValueError("cannot parse JVM options in " + variable) from None
        if any(option.startswith("-D") and option[2:].split("=", 1)[0] in overrides for option in options):
            raise ValueError("unreviewed TLS property override in " + variable)


def validate_java_truststore(home):
    home = Path(home)
    # JSSE selects jssecacerts before cacerts. Check the store actually selected.
    directory = home / "lib/security"
    store = directory / "jssecacerts"
    if not store.exists():
        store = directory / "cacerts"
    if not store.is_file():
        raise ValueError("default Java truststore is missing")
    keytool = home / "bin" / ("keytool.exe" if os.name == "nt" else "keytool")
    # changeit is the public default truststore password; never read app secrets.
    result = subprocess.run([str(keytool), "-list", "-rfc", "-keystore", str(store),
                             "-storepass", "changeit"], capture_output=True, text=True, check=True, timeout=30)
    return {"selectedStore": store.name, "checkedCertificates": validate_pem(result.stdout)}


def check_java(executable):
    validate_java_options(os.environ)
    executable = shutil.which(executable)
    if not executable:
        raise ValueError("Java executable not found")
    result = subprocess.run([executable, "-XshowSettings:properties", "-version"],
                            capture_output=True, text=True, check=True, timeout=20)
    properties = dict(re.findall(r"^\s+([\w.]+) = (.*)$", result.stderr, re.M))
    home = properties.get("java.home")
    if not home or properties.get("java.specification.version") != "21":
        raise ValueError("reviewed Java 21 runtime required")
    if Path(executable).resolve() != (Path(home) / "bin" / Path(executable).name).resolve():
        raise ValueError("Java executable and effective java.home disagree")
    return {"javaVersion": properties.get("java.runtime.version"), **validate_java_truststore(home)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runtime", choices=("python", "java"))
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    try:
        details = check_python() if args.runtime == "python" else check_java(args.java)
        print(json.dumps({"type": "ca_trust_preflight", "runtime": args.runtime, "status": "PASS", **details}))
        return 0
    except Exception as error:
        # Subprocess/import/SSL exceptions can echo inherited option values or
        # credential-bearing paths. Only our own policy errors are safe to print.
        reason = str(error) if type(error) is ValueError else type(error).__name__
        print(json.dumps({"type": "ca_trust_preflight", "runtime": args.runtime,
                          "status": "FAIL", "reason": reason}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
