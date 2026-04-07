#!/bin/bash
# Regenerates ecosim-uml.pdf from the PlantUML source files.
# Requirements: plantuml, rsvg-convert (brew install librsvg)
set -e
cd "$(dirname "$0")"

echo "Generating SVGs..."
plantuml -tsvg class-diagram.puml
plantuml -tsvg sequence-diagram.puml

echo "Converting to PDF..."
rsvg-convert -f pdf -o class-diagram.pdf    "EcoSim Class Diagram.svg"
rsvg-convert -f pdf -o sequence-diagram.pdf "EcoSim Sequence Diagram.svg"

echo "Combining into ecosim-uml.pdf..."
"/System/Library/Automator/Combine PDF Pages.action/Contents/MacOS/join" \
    -o ecosim-uml.pdf class-diagram.pdf sequence-diagram.pdf

echo "Done: $(pwd)/ecosim-uml.pdf"
