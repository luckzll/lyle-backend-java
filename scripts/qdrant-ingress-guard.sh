#!/usr/bin/env bash
# Block external Qdrant traffic without interrupting Docker peers or SSH tunnels.
# Usage: qdrant-ingress-guard.sh [apply|remove] [external-interface]
set -euo pipefail
mode=${1:-apply}
iface=${2:-eth0}
[[ "$mode" == apply || "$mode" == remove ]] || exit 2
[[ "$iface" =~ ^[a-zA-Z0-9_.:-]+$ ]] || exit 2
ip link show dev "$iface" >/dev/null

rule() {
    local tool=$1 chain=$2
    shift 2
    if [[ "$mode" == apply ]]; then
        "$tool" -w -C "$chain" "$@" 2>/dev/null || "$tool" -w -I "$chain" 1 "$@"
    else
        if "$tool" -w -C "$chain" "$@" 2>/dev/null; then
            "$tool" -w -D "$chain" "$@"
        fi
    fi
}

for tool in iptables ip6tables; do
    # INPUT also covers the IPv6 docker-proxy path on IPv4-only bridges.
    rule "$tool" INPUT -i "$iface" -p tcp -m multiport --dports 6333,6334 -m comment --comment lyle-qdrant-ingress -j DROP
    if "$tool" -w -S DOCKER-USER >/dev/null 2>&1; then
        rule "$tool" DOCKER-USER -i "$iface" -p tcp -m multiport --dports 6333,6334 -m comment --comment lyle-qdrant-ingress -j DROP
    fi
done
