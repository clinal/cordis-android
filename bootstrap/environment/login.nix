{ gperftools, writeScriptBin }:

writeScriptBin "login" ''
  set -e

  if [ -n "$CORDIS_DNS" ]; then
    echo "nameserver $CORDIS_DNS" > /etc/resolv.conf
  fi

  if [ -n "$CORDIS_TIMEZONE" ] && [ -e /etc/zoneinfo ]; then
    /bin/ln -sf "/etc/zoneinfo/$CORDIS_TIMEZONE" /etc/localtime
  fi

  for var in $(/bin/env | /bin/cut -d '=' -f 1); do
    unset "$var"
  done

  export PATH=/bin
  export HOME=/home
  export LD_PRELOAD=${gperftools}/lib/libtcmalloc_minimal.so

  cd "$HOME"
  exec sh "$@"
''
