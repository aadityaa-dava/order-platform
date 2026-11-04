#!/usr/bin/env bash
# Records the conditions the numbers were measured under.
echo "date:            $(date '+%Y-%m-%d %H:%M')"
echo "machine:         $(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m), $(sysctl -n hw.ncpu) cores, $(( $(sysctl -n hw.memsize) / 1073741824 )) GB RAM"
echo "macOS:           $(sw_vers -productVersion)"
echo "docker:          $(docker info --format '{{.NCPU}} CPUs, {{.MemTotal}} bytes memory for the VM')"
echo "java:            $(java -version 2>&1 | head -n 1)"
echo "k6:              $(k6 version | head -n 1)"
echo "everything runs on this one machine: k6, four JVMs, Kafka, PostgreSQL x4, Redis, Prometheus, Grafana, Jaeger"
