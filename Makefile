# sane-graph-edit Makefile
#
# Thin wrapper around Gradle for people who reach for `make` first.
# Everything delegates to ./gradlew so the JDK toolchain, dependency
# resolution, and incremental compilation all work exactly as they
# would with a bare Gradle invocation.
#
# Targets:
#   make            - compile + test
#   make run        - launch the editor
#   make test       - run unit tests
#   make jar        - build the fat jar (~2 MB, needs JDK 21+ to run)
#   make package    - build a native app-image via jpackage (~160 MB, no Java needed)
#   make deb        - build a .deb installer
#   make rpm        - build a .rpm installer
#   make install    - install the native app-image to PREFIX (default /usr/local)
#   make uninstall  - remove what `make install` put in place
#   make release    - clean build + test + fat jar + native app-image
#   make clean      - remove all build artefacts

PREFIX     ?= /usr/local
APPDIR     := $(PREFIX)/lib/sane-graph-edit
BINLINK    := $(PREFIX)/bin/sane-graph-edit
GRADLE     := ./gradlew
BUILD_DIST := build/dist/sane-graph-edit

.PHONY: all run test jar package deb rpm install uninstall release clean

# --- primary targets ---

all: test
	$(GRADLE) build

run:
	$(GRADLE) run

test:
	$(GRADLE) test

# --- packaging ---

jar:
	$(GRADLE) fatJar

package:
	$(GRADLE) jpackage

deb:
	$(GRADLE) jpackage -Ptype=deb

rpm:
	$(GRADLE) jpackage -Ptype=rpm

# --- install / uninstall (from app-image) ---

install: package
	@echo "Installing to $(APPDIR) ..."
	install -d $(APPDIR)
	cp -a $(BUILD_DIST)/. $(APPDIR)/
	install -d $(dir $(BINLINK))
	ln -sf $(APPDIR)/bin/sane-graph-edit $(BINLINK)
	@echo "Installed. Run with: sane-graph-edit"

uninstall:
	rm -f  $(BINLINK)
	rm -rf $(APPDIR)
	@echo "Uninstalled."

# --- release (everything from scratch) ---

release: clean
	$(GRADLE) build
	$(GRADLE) fatJar
	$(GRADLE) jpackage
	@echo ""
	@echo "Release artefacts:"
	@echo "  Fat jar:    build/libs/sane-graph-edit-*-all.jar"
	@echo "  App image:  $(BUILD_DIST)/"
	@echo ""

# --- housekeeping ---

clean:
	$(GRADLE) clean
	rm -rf build/dist
