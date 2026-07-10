#!/usr/bin/env python3
"""
Generate per-client AmneziaVPN (AWG) keys from a server template.

Usage:
    python3 gen_client_key.py --template <existing_vpn_key> --client-ip 10.8.1.3 --description "Client Name"

Requirements:
    pip install cryptography

The server-level AWG obfuscation params and endpoint are reused from the template.
Each client gets a fresh WireGuard keypair and preshared key.
The client's public key must be added to the server's WireGuard peers config separately.
"""

import argparse
import base64
import json
import os
import struct
import zlib

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey


def decode_vpn_key(vpn_key: str) -> dict:
    """Decode a vpn:// key into its JSON config."""
    raw = vpn_key.removeprefix("vpn://")
    padded = raw + "=" * (4 - len(raw) % 4)
    decoded = base64.urlsafe_b64decode(padded)
    # First 4 bytes: 2-byte zero padding + 2-byte big-endian length (uncompressed size)
    decompressed = zlib.decompress(decoded[4:])
    return json.loads(decompressed)


def encode_vpn_key(config: dict) -> str:
    """Encode a config dict into a vpn:// key."""
    data = json.dumps(config, ensure_ascii=False).encode("utf-8")
    compressed = zlib.compress(data)
    # 4-byte header: 2 zero bytes + big-endian uint16 of uncompressed length
    header = struct.pack(">HH", 0, len(data))
    payload = header + compressed
    return "vpn://" + base64.urlsafe_b64encode(payload).decode().rstrip("=")


def wg_genkey() -> tuple[str, str]:
    """Generate a WireGuard private/public key pair using Python cryptography."""
    priv = X25519PrivateKey.generate()
    priv_bytes = priv.private_bytes_raw()
    pub_bytes = priv.public_key().public_bytes_raw()
    return (
        base64.b64encode(priv_bytes).decode(),
        base64.b64encode(pub_bytes).decode(),
    )


def wg_genpsk() -> str:
    """Generate a WireGuard preshared key (32 random bytes, base64)."""
    return base64.b64encode(os.urandom(32)).decode()


def generate_client_key(template_key: str, client_ip: str, description: str) -> str:
    """
    Generate a new per-client vpn:// key reusing server config from a template key.

    Args:
        template_key: An existing vpn:// key from the same server
        client_ip:    The client's IP address (e.g. "10.8.1.3")
        description:  Human-readable label for this client

    Returns:
        A new vpn:// key string for the client.
        Print the client's public key — it must be added to the server as a peer.
    """
    config = decode_vpn_key(template_key)

    # Extract server-level settings from the template's last_config
    container = config["containers"][0]
    proto_key = next(k for k in container if k != "container")
    proto = container[proto_key]
    last_cfg = json.loads(proto["last_config"])

    server_pub_key = last_cfg["server_pub_key"]
    hostname = last_cfg["hostName"]
    port = last_cfg["port"]
    mtu = last_cfg.get("mtu", "1420")
    keepalive = last_cfg.get("persistent_keep_alive", "25")

    # AWG obfuscation params — identical for all clients on this server
    awg_params = {k: last_cfg[k] for k in ("H1", "H2", "H3", "H4",
                                             "S1", "S2", "S3", "S4",
                                             "Jc", "Jmin", "Jmax",
                                             "I1", "I2", "I3", "I4", "I5")
                  if k in last_cfg}

    # Generate fresh per-client credentials
    client_priv, client_pub = wg_genkey()
    psk = wg_genpsk()

    # Build the [Interface]+[Peer] config string
    awg_param_lines = "\n".join(f"{k} = {v}" for k, v in awg_params.items())
    wg_config = (
        f"[Interface]\n"
        f"Address = {client_ip}/32\n"
        f"DNS = $PRIMARY_DNS, $SECONDARY_DNS\n"
        f"PrivateKey = {client_priv}\n"
        f"MTU = {mtu}\n"
        f"{awg_param_lines}\n\n"
        f"[Peer]\n"
        f"PublicKey = {server_pub_key}\n"
        f"PresharedKey = {psk}\n"
        f"AllowedIPs = 0.0.0.0/0, ::/0\n"
        f"Endpoint = {hostname}:{port}\n"
        f"PersistentKeepalive = {keepalive}\n"
    )

    new_last_cfg = {
        **awg_params,
        "allowed_ips": ["0.0.0.0/0", "::/0"],
        "clientId": client_pub,
        "client_ip": client_ip,
        "client_priv_key": client_priv,
        "client_pub_key": client_pub,
        "config": wg_config,
        "hostName": hostname,
        "mtu": mtu,
        "persistent_keep_alive": keepalive,
        "port": port,
        "psk_key": psk,
        "server_pub_key": server_pub_key,
    }

    new_proto = {k: v for k, v in proto.items() if k != "last_config"}
    new_proto["last_config"] = json.dumps(new_last_cfg, indent=4)

    new_config = {
        **config,
        "description": description,
        "containers": [{**container, proto_key: new_proto}],
    }

    return encode_vpn_key(new_config), client_pub


def main():
    parser = argparse.ArgumentParser(description="Generate a per-client AmneziaVPN key")
    parser.add_argument("--template", required=True, help="Existing vpn:// key (from any client on this server)")
    parser.add_argument("--client-ip", required=True, help="Client IP, e.g. 10.8.1.3")
    parser.add_argument("--description", default="Client", help="Label for this client")
    args = parser.parse_args()

    new_key, pub_key = generate_client_key(args.template, args.client_ip, args.description)

    print(f"\n=== Client public key (ADD THIS TO YOUR SERVER) ===")
    print(f"{pub_key}")
    print(f"\nServer command (run on server):")
    print(f"  sudo wg set <wg-interface> peer {pub_key} allowed-ips {args.client_ip}/32")
    print(f"\n=== New vpn:// key for client ===")
    print(new_key)


if __name__ == "__main__":
    main()
