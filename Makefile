# sane-graph-edit Makefile
#
# Thin wrapper around Gradle for people who reach for `make` first.
# Everything delegates to ./gradlew so the JDK toolchain, dependency
# resolution, and incremental compilation all work exactly as they
# would with a bare Gradle invocation.
#
# Targets:
#   make            - compile + test
#   make run        - build + launch the editor (the Electron client); ARGS="file.dot" is passed on
#   make electron   - build the Electron client without launching it
#   make run-swing  - launch the Swing client (via Gradle)
#   make run-jar    - build fat jar + launch it directly (the Swing client, faster startup)
#   make test       - run unit tests
#   make jar        - build the fat jar (~2 MB, needs JDK 21+ to run)
#   make package    - build a native app-image via jpackage (~160 MB, no Java needed)
#   make appimage   - build a single .AppImage file (~54 MB, no Java needed)
#   make deb        - build a .deb installer
#   make rpm        - build a .rpm installer
#   make install    - install a launcher of the Electron client in this tree to PREFIX (default /usr/local)
#   make install-swing - install the Swing client's native app-image to PREFIX
#   make uninstall  - remove what either of them put in place
#   make release    - clean build + test + fat jar + AppImage
#   make clean      - remove all build artefacts

PREFIX     ?= /usr/local
APPDIR     := $(PREFIX)/lib/sane-graph-edit
BINLINK    := $(PREFIX)/bin/sane-graph-edit
GRADLE     := ./gradlew
BUILD_DIST := build/dist/sane-graph-edit
FAT_JAR    := build/libs/sane-graph-edit-*-all.jar
ELECTRON   := modules/client-electron
LAUNCHER   := build/sane-graph-edit-launcher
ARGS       ?=

.PHONY: all compile run electron run-swing run-jar test jar package appimage deb rpm install install-swing uninstall release clean

# --- primary targets ---

all: test
	$(GRADLE) build

compile:
	$(GRADLE) compileKotlin

# started from here, not from the client's directory: the working directory is the workspace
run: electron
	$(ELECTRON)/node_modules/.bin/electron --no-sandbox $(ELECTRON) $(ARGS)

# the client core (Kotlin, built for Node) first: the client's packages include it
electron:
	$(GRADLE) :editor-host:jsNodeDevelopmentLibraryDistribution
	cd $(ELECTRON) && npm install && npm run build

run-swing:
	$(GRADLE) run

run-jar: jar
	java -jar $(FAT_JAR)

test:
	$(GRADLE) test

# --- packaging ---

jar:
	$(GRADLE) fatJar

package:
	$(GRADLE) jpackage

appimage:
	$(GRADLE) appimage

deb:
	$(GRADLE) jpackage -Ptype=deb

rpm:
	$(GRADLE) jpackage -Ptype=rpm

# --- install / uninstall ---

# The Electron client is not packaged yet: what is installed is a launcher of the
# client in this source tree, so the tree has to stay, and what is built in it is what runs.
# It builds nothing (`make electron` does): run as root, a build would leave root's files in the tree.
install:
	install -d build $(dir $(BINLINK))
	printf '#!/bin/sh\nexec "%s/node_modules/.bin/electron" --no-sandbox "%s" "$$@"\n' \
		"$(abspath $(ELECTRON))" "$(abspath $(ELECTRON))" > $(LAUNCHER)
	# not through the link a Swing install left there
	rm -f $(BINLINK)
	install -m 755 $(LAUNCHER) $(BINLINK)
	@echo "Installed a launcher of $(abspath $(ELECTRON)). Run with: sane-graph-edit"

# the Swing client, from the app-image
install-swing: package
	@echo "Installing to $(APPDIR) ..."
	install -d $(APPDIR)
	# replace files instead of writing into them: a running editor keeps its
	# old launcher, and overwriting that in place fails with "Text file busy"
	cp -a --remove-destination $(BUILD_DIST)/. $(APPDIR)/
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
	$(GRADLE) appimage
	@echo ""
	@echo "Release artefacts:"
	@echo "  Fat jar:    build/libs/sane-graph-edit-*-all.jar"
	@echo "  AppImage:   build/dist/sane-graph-edit-*-x86_64.AppImage"
	@echo ""

# --- housekeeping ---

clean:
	$(GRADLE) clean
	rm -rf build/dist
