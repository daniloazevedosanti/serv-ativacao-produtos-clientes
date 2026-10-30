#!/bin/sh
# ----------------------------------------------------------------------------
# Maven Wrapper (somente script): garante a mesma versão do Maven em qualquer máquina.
# Baixa a distribuição definida em .mvn/wrapper/maven-wrapper.properties para
# ~/.m2/wrapper/dists na primeira execução e delega para ela.
# ----------------------------------------------------------------------------
set -e

BASEDIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
PROPS="$BASEDIR/.mvn/wrapper/maven-wrapper.properties"

distributionUrl="${MVNW_DISTRIBUTION_URL:-$(sed -n 's/^distributionUrl=//p' "$PROPS" | tr -d '\r')}"
if [ -z "$distributionUrl" ]; then
  echo "mvnw: distributionUrl não encontrado em $PROPS" >&2
  exit 1
fi

distFile=$(basename "$distributionUrl")
distName=$(printf '%s' "$distFile" | sed -e 's/-bin\.tar\.gz$//' -e 's/-bin\.zip$//')
urlId=$(printf '%s' "$distributionUrl" | cksum | cut -d' ' -f1)
MAVEN_USER_HOME="${MAVEN_USER_HOME:-$HOME/.m2}"
MAVEN_HOME="$MAVEN_USER_HOME/wrapper/dists/$distName/$urlId"

if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
  tmpDir=$(mktemp -d)
  trap 'rm -rf "$tmpDir"' EXIT
  echo "mvnw: baixando $distributionUrl" >&2
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$tmpDir/$distFile" "$distributionUrl"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$tmpDir/$distFile" "$distributionUrl"
  else
    echo "mvnw: é necessário curl ou wget para baixar o Maven" >&2
    exit 1
  fi
  case "$distFile" in
    *.zip) unzip -q "$tmpDir/$distFile" -d "$tmpDir" ;;
    *) tar -xzf "$tmpDir/$distFile" -C "$tmpDir" ;;
  esac
  mkdir -p "$(dirname "$MAVEN_HOME")"
  # outra execução simultânea pode ter terminado antes: não falha nesse caso
  mv "$tmpDir/$distName" "$MAVEN_HOME" 2>/dev/null || [ -x "$MAVEN_HOME/bin/mvn" ]
  chmod +x "$MAVEN_HOME/bin/mvn"
fi

export MAVEN_PROJECTBASEDIR="$BASEDIR"
exec "$MAVEN_HOME/bin/mvn" "$@"
