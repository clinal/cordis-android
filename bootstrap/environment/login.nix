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
    case "$var" in
      CORDIS_INSTANCE_ENV_*) ;;
      *) unset "$var" ;;
    esac
  done

  for var in $(/bin/env | /bin/cut -d '=' -f 1); do
    case "$var" in
      CORDIS_INSTANCE_ENV_*)
        name="''${var#CORDIS_INSTANCE_ENV_}"
        eval "value=\''${$var}"
        export "$name=$value"
        unset "$var"
        ;;
    esac
  done

  export PATH=/bin
  export HOME=/home
  # Work around proot allocator corruption on Android 15: https://github.com/termux/proot/issues/313
  export LD_PRELOAD=${gperftools}/lib/libtcmalloc_minimal.so

  cd "$HOME"
  exec sh "$@"
''
