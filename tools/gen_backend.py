#!/usr/bin/env python3
"""Writes src/com/nota/data/Backend.java for one deployment.

    ./tools/gen_backend.py https://host:8443 <secret>

The secret is stored XOR-ed against a random key, both as int arrays, so it is not a string
in the dex pool and `strings` over the APK does not turn it up. That is the whole claim:
somebody who unpacks and reads the code still gets it, because the key has to ship too.
"""

import os
import random
import sys

TEMPLATE = '''package com.nota.data;

/**
 * The stream helper this build talks to. Kept out of the repository: the address and the
 * shared secret belong to one deployment, and published they are a free proxy for anyone.
 *
 * <p>Written by {@code tools/gen_backend.py}; edit that rather than this file.
 */
final class Backend {
    private Backend() {
    }

    /**
     * Base address of the notastream helper, no trailing slash. Not hidden, because it
     * cannot be: the certificate is pinned to this address, and a pin is a plain string in
     * the resources that the framework reads before any of this code runs.
     */
    static final String HOST = "%(host)s";

    /**
     * Shared with the helper. It ships inside the APK, so it is not a real credential: a
     * reader who unpacks the build can have it. Keeping it out of the string pool only
     * means that scraping it is no longer a one-line job, and both ends can be handed a new
     * one the day it shows up somewhere.
     */
    static String secret() {
        char[] out = new char[BODY.length];
        for (int i = 0; i < BODY.length; i++) {
            out[i] = (char) (BODY[i] ^ KEY[i %% KEY.length]);
        }
        return new String(out);
    }

    private static final int[] KEY = {%(key)s};

    private static final int[] BODY = {%(body)s};
}
'''


def wrap(numbers, indent=12, width=96):
    lines, row = [], ""
    for n in numbers:
        piece = ("%d, " % n)
        if len(row) + len(piece) > width - indent:
            lines.append(row.rstrip())
            row = ""
        row += piece
    lines.append(row.rstrip().rstrip(","))
    glue = "\n" + " " * indent
    return glue + glue.join(lines) if len(lines) > 1 else lines[0]


def main():
    if len(sys.argv) != 3:
        print(__doc__.strip(), file=sys.stderr)
        return 1
    host, secret = sys.argv[1], sys.argv[2]
    key = [random.randrange(1, 256) for _ in range(16)]
    body = [ord(c) ^ key[i % len(key)] for i, c in enumerate(secret)]
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "src", "com", "nota", "data", "Backend.java")
    with open(out, "w") as f:
        f.write(TEMPLATE % {"host": host, "key": wrap(key), "body": wrap(body)})
    print("wrote %s" % os.path.normpath(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
