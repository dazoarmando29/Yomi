#!/usr/bin/env python3
"""Deterministic Xcode project generator for the Yomi iOS app.

Produces ios/Yomi.xcodeproj/project.pbxproj from the sources in ios/YomiApp
plus the local Swift package ios/YomiKit. Runs on any platform with Python 3 —
no Xcode or third-party tooling required. Re-run after adding/removing source
files, then commit the regenerated project.

Usage: python ios/tools/generate_xcodeproj.py [--check]

--check verifies an existing project against the sources without rewriting it
(used by CI and local validation).
"""
import argparse
import hashlib
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # ios/
APP_DIR = os.path.join(ROOT, "YomiApp")
PROJECT_DIR = os.path.join(ROOT, "Yomi.xcodeproj")
PROJECT_FILE = os.path.join(PROJECT_DIR, "project.pbxproj")
SCHEME_DIR = os.path.join(PROJECT_DIR, "xcshareddata", "xcschemes")
SCHEME_FILE = os.path.join(SCHEME_DIR, "Yomi.xcscheme")

APP_GROUP_DIRS = ["Theme", "Library", "Browse", "MangaDetails", "Reader", "History", "Downloads", "Settings"]
RESOURCE_DIRS = ["Resources"]

TARGET_NAME = "Yomi"
PRODUCT_BUNDLE_ID = "app.yomi.reader"
DEPLOYMENT_TARGET = "17.0"
MARKETING_VERSION = "1.0"


def object_id(*parts: str) -> str:
    """Deterministic 24-char uppercase-hex object identifier."""
    digest = hashlib.sha1("::".join(parts).encode("utf-8")).hexdigest()[:24].upper()
    return digest


class ProjectBuilder:
    def __init__(self) -> None:
        self.swift_files: list[tuple[str, str]] = []  # (group_path, filename)
        self.resource_files: list[tuple[str, str]] = []
        self.collect()

    def collect(self) -> None:
        for name in sorted(os.listdir(APP_DIR)):
            full = os.path.join(APP_DIR, name)
            if os.path.isfile(full) and name.endswith(".swift"):
                self.swift_files.append(("", name))
        for subdir in APP_GROUP_DIRS + RESOURCE_DIRS:
            full = os.path.join(APP_DIR, subdir)
            if not os.path.isdir(full):
                continue
            for name in sorted(os.listdir(full)):
                path = os.path.join(full, name)
                if name.endswith(".swift") and os.path.isfile(path):
                    self.swift_files.append((subdir, name))
                elif name.endswith(".xcassets") and os.path.isdir(path):
                    self.resource_files.append((subdir, name))

    # ---- helpers -----------------------------------------------------------

    def file_ref(self, group: str, name: str) -> str:
        return object_id("fileref", group, name)

    def build_file(self, group: str, name: str) -> str:
        return object_id("buildfile", group, name)

    def group_id(self, group: str) -> str:
        return object_id("group", group)

    # ---- sections ----------------------------------------------------------

    def pbx_build_file(self) -> str:
        lines = ["/* Begin PBXBuildFile section */"]
        for group, name in self.swift_files:
            ref = self.file_ref(group, name)
            bid = self.build_file(group, name)
            lines.append(f"\t\t{bid} /* {name} in Sources */ = {{isa = PBXBuildFile; fileRef = {ref} /* {name} */; }};")
        for group, name in self.resource_files:
            ref = self.file_ref(group, name)
            bid = self.build_file(group, name)
            lines.append(f"\t\t{bid} /* {name} in Resources */ = {{isa = PBXBuildFile; fileRef = {ref} /* {name} */; }};")
        kit_build = object_id("buildfile", "YomiKit-product")
        kit_product = object_id("productdep", "YomiKit")
        lines.append(
            f"\t\t{kit_build} /* YomiKit in Frameworks */ = {{isa = PBXBuildFile; productRef = {kit_product} /* YomiKit */; }};"
        )
        lines.append("/* End PBXBuildFile section */")
        return "\n".join(lines)

    def pbx_file_reference(self) -> str:
        lines = ["/* Begin PBXFileReference section */"]
        for group, name in self.swift_files:
            ref = self.file_ref(group, name)
            lines.append(
                f"\t\t{ref} /* {name} */ = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = \"{name}\"; sourceTree = \"<group>\"; }};"
            )
        for group, name in self.resource_files:
            ref = self.file_ref(group, name)
            lines.append(
                f"\t\t{ref} /* {name} */ = {{isa = PBXFileReference; lastKnownFileType = folder.assetcatalog; path = \"{name}\"; sourceTree = \"<group>\"; }};"
            )
        app_ref = object_id("fileref", "product", "Yomi.app")
        lines.append(
            f"\t\t{app_ref} /* Yomi.app */ = {{isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = Yomi.app; sourceTree = BUILT_PRODUCTS_DIR; }};"
        )
        lines.append("/* End PBXFileReference section */")
        return "\n".join(lines)

    def pbx_frameworks(self) -> str:
        kit_build = object_id("buildfile", "YomiKit-product")
        return "\n".join([
            "/* Begin PBXFrameworksBuildPhase section */",
            f"\t\t{object_id('phase', 'frameworks')} /* Frameworks */ = {{",
            "\t\t\tisa = PBXFrameworksBuildPhase;",
            "\t\t\tbuildActionMask = 2147483647;",
            f"\t\t\tfiles = (\n\t\t\t\t{kit_build} /* YomiKit in Frameworks */,\n\t\t\t);",
            "\t\t\trunOnlyForDeploymentPostprocessing = 0;",
            "\t\t};",
            "/* End PBXFrameworksBuildPhase section */",
        ])

    def pbx_group(self) -> str:
        lines = ["/* Begin PBXGroup section */"]
        app_group = self.group_id("YomiApp")
        products_group = self.group_id("Products")
        main_group = self.group_id("main")

        def children_for(group: str) -> str:
            entries = []
            for g, name in self.swift_files:
                # Top-level files (g == "") live directly in the YomiApp group
                if g == group or (group == "YomiApp" and g == ""):
                    entries.append((name, self.file_ref(g, name)))
            for g, name in self.resource_files:
                if g == group or (group == "YomiApp" and g == ""):
                    entries.append((name, self.file_ref(g, name)))
            if group == "YomiApp":
                for sub in APP_GROUP_DIRS + RESOURCE_DIRS:
                    if any(g == sub for g, _ in self.swift_files + self.resource_files):
                        entries.append((sub, self.group_id(sub)))
            rendered = "".join(f"\t\t\t\t{oid} /* {name} */,\n" for name, oid in entries)
            return rendered

        lines.append(f"\t\t{main_group} = {{")
        lines.append("\t\t\tisa = PBXGroup;")
        lines.append("\t\t\tchildren = (")
        lines.append(f"\t\t\t\t{app_group} /* YomiApp */,")
        lines.append(f"\t\t\t\t{products_group} /* Products */,")
        lines.append("\t\t\t);")
        lines.append("\t\t\tsourceTree = \"<group>\";")
        lines.append("\t\t};")

        lines.append(f"\t\t{app_group} /* YomiApp */ = {{")
        lines.append("\t\t\tisa = PBXGroup;")
        lines.append("\t\t\tchildren = (")
        lines.append(children_for("YomiApp"))
        lines.append("\t\t\t);")
        lines.append("\t\t\tpath = YomiApp;")
        lines.append("\t\t\tsourceTree = \"<group>\";")
        lines.append("\t\t};")

        for sub in APP_GROUP_DIRS + RESOURCE_DIRS:
            if not any(g == sub for g, _ in self.swift_files + self.resource_files):
                continue
            lines.append(f"\t\t{self.group_id(sub)} /* {sub} */ = {{")
            lines.append("\t\t\tisa = PBXGroup;")
            lines.append("\t\t\tchildren = (")
            lines.append(children_for(sub))
            lines.append("\t\t\t);")
            lines.append(f"\t\t\tpath = {sub};")
            lines.append("\t\t\tsourceTree = \"<group>\";")
            lines.append("\t\t};")

        lines.append(f"\t\t{products_group} /* Products */ = {{")
        lines.append("\t\t\tisa = PBXGroup;")
        lines.append("\t\t\tchildren = (")
        lines.append(f"\t\t\t\t{object_id('fileref', 'product', 'Yomi.app')} /* Yomi.app */,")
        lines.append("\t\t\t);")
        lines.append("\t\t\tname = Products;")
        lines.append("\t\t\tsourceTree = \"<group>\";")
        lines.append("\t\t};")
        lines.append("/* End PBXGroup section */")
        return "\n".join(lines)

    def pbx_native_target(self) -> str:
        product_dep = object_id("productdep", "YomiKit")
        return "\n".join([
            "/* Begin PBXNativeTarget section */",
            f"\t\t{object_id('target', 'Yomi')} /* Yomi */ = {{",
            "\t\t\tisa = PBXNativeTarget;",
            f"\t\t\tbuildConfigurationList = {object_id('configlist', 'target')} /* Build configuration list for PBXNativeTarget \"Yomi\" */;",
            "\t\t\tbuildPhases = (",
            f"\t\t\t\t{object_id('phase', 'sources')} /* Sources */,",
            f"\t\t\t\t{object_id('phase', 'frameworks')} /* Frameworks */,",
            f"\t\t\t\t{object_id('phase', 'resources')} /* Resources */,",
            "\t\t\t);",
            "\t\t\tbuildRules = (",
            "\t\t\t);",
            "\t\t\tdependencies = (",
            "\t\t\t);",
            "\t\t\tname = Yomi;",
            "\t\t\tpackageProductDependencies = (",
            f"\t\t\t\t{product_dep} /* YomiKit */,",
            "\t\t\t);",
            "\t\t\tproductName = Yomi;",
            f"\t\t\tproductReference = {object_id('fileref', 'product', 'Yomi.app')} /* Yomi.app */;",
            "\t\t\tproductType = \"com.apple.product-type.application\";",
            "\t\t};",
            "/* End PBXNativeTarget section */",
        ])

    def pbx_project(self) -> str:
        return "\n".join([
            "/* Begin PBXProject section */",
            f"\t\t{object_id('project', 'root')} /* Project object */ = {{",
            "\t\t\tisa = PBXProject;",
            "\t\t\tattributes = {",
            "\t\t\t\tBuildIndependentTargetsInParallel = 1;",
            "\t\t\t\tLastSwiftUpdateCheck = 1500;",
            "\t\t\t\tLastUpgradeCheck = 1500;",
            "\t\t\t\tTargetAttributes = {",
            f"\t\t\t\t\t{object_id('target', 'Yomi')} = {{",
            "\t\t\t\t\t\tCreatedOnToolsVersion = 15.0;",
            "\t\t\t\t\t};",
            "\t\t\t\t};",
            "\t\t\t};",
            f"\t\t\tbuildConfigurationList = {object_id('configlist', 'project')} /* Build configuration list for PBXProject \"Yomi\" */;",
            "\t\t\tcompatibilityVersion = \"Xcode 14.0\";",
            "\t\t\tdevelopmentRegion = en;",
            "\t\t\thasScannedForEncodings = 0;",
            "\t\t\tknownRegions = (",
            "\t\t\t\ten,",
            "\t\t\t\tBase,",
            "\t\t\t);",
            f"\t\t\tmainGroup = {object_id('group', 'main')};",
            f"\t\t\tpackageReferences = (\n\t\t\t\t{object_id('packageref', 'YomiKit')} /* XCLocalSwiftPackageReference \"YomiKit\" */,\n\t\t\t);",
            f"\t\t\tproductRefGroup = {object_id('group', 'Products')} /* Products */;",
            "\t\t\tprojectDirPath = \"\";",
            "\t\t\tprojectRoot = \"\";",
            "\t\t\ttargets = (",
            f"\t\t\t\t{object_id('target', 'Yomi')} /* Yomi */,",
            "\t\t\t);",
            "\t\t};",
            "/* End PBXProject section */",
        ])

    def pbx_resources(self) -> str:
        files = []
        for group, name in self.resource_files:
            files.append(f"\t\t\t\t{self.build_file(group, name)} /* {name} in Resources */,")
        return "\n".join([
            "/* Begin PBXResourcesBuildPhase section */",
            f"\t\t{object_id('phase', 'resources')} /* Resources */ = {{",
            "\t\t\tisa = PBXResourcesBuildPhase;",
            "\t\t\tbuildActionMask = 2147483647;",
            "\t\t\tfiles = (",
            *files,
            "\t\t\t);",
            "\t\t\trunOnlyForDeploymentPostprocessing = 0;",
            "\t\t};",
            "/* End PBXResourcesBuildPhase section */",
        ])

    def pbx_sources(self) -> str:
        files = []
        for group, name in self.swift_files:
            files.append(f"\t\t\t\t{self.build_file(group, name)} /* {name} in Sources */,")
        return "\n".join([
            "/* Begin PBXSourcesBuildPhase section */",
            f"\t\t{object_id('phase', 'sources')} /* Sources */ = {{",
            "\t\t\tisa = PBXSourcesBuildPhase;",
            "\t\t\tbuildActionMask = 2147483647;",
            "\t\t\tfiles = (",
            *files,
            "\t\t\t);",
            "\t\t\trunOnlyForDeploymentPostprocessing = 0;",
            "\t\t};",
            "/* End PBXSourcesBuildPhase section */",
        ])

    def xc_build_configuration(self) -> str:
        common_project = [
            ("ALWAYS_SEARCH_USER_PATHS", "NO"),
            ("ASSETCATALOG_COMPILER_GENERATE_SWIFT_ASSET_SYMBOL_EXTENSIONS", "YES"),
            ("CLANG_ANALYZER_NONNULL", "YES"),
            ("CLANG_ENABLE_MODULES", "YES"),
            ("CLANG_ENABLE_OBJC_ARC", "YES"),
            ("COPY_PHASE_STRIP", "NO"),
            ("ENABLE_STRICT_OBJC_MSGSEND", "YES"),
            ("GCC_NO_COMMON_BLOCKS", "YES"),
            ("IPHONEOS_DEPLOYMENT_TARGET", DEPLOYMENT_TARGET),
            ("SDKROOT", "iphoneos"),
            ("SWIFT_EMIT_LOC_STRINGS", "YES"),
        ]
        debug_project = common_project + [
            ("DEBUG_INFORMATION_FORMAT", "dwarf"),
            ("ENABLE_TESTABILITY", "YES"),
            ("GCC_OPTIMIZATION_LEVEL", "0"),
            ("GCC_PREPROCESSOR_DEFINITIONS", '("DEBUG=1", "$(inherited)",)'),
            ("ONLY_ACTIVE_ARCH", "YES"),
            ("SWIFT_ACTIVE_COMPILATION_CONDITIONS", '"DEBUG $(inherited)"'),
            ("SWIFT_OPTIMIZATION_LEVEL", '"-Onone"'),
        ]
        release_project = common_project + [
            ("DEBUG_INFORMATION_FORMAT", '"dwarf-with-dsym"'),
            ("ENABLE_NS_ASSERTIONS", "NO"),
            ("SWIFT_COMPILATION_MODE", "wholemodule"),
            ("SWIFT_OPTIMIZATION_LEVEL", '"-O"'),
            ("VALIDATE_PRODUCT", "YES"),
        ]
        target_common = [
            ("ASSETCATALOG_COMPILER_APPICON_NAME", "AppIcon"),
            ("ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME", "AccentColor"),
            ("CODE_SIGN_STYLE", "Automatic"),
            ("CURRENT_PROJECT_VERSION", "1"),
            ("ENABLE_PREVIEWS", "YES"),
            ("GENERATE_INFOPLIST_FILE", "YES"),
            ("INFOPLIST_KEY_CFBundleDisplayName", "Yomi"),
            ('INFOPLIST_KEY_LSApplicationCategoryType', '"public.app-category.books"'),
            ("INFOPLIST_KEY_UIApplicationSceneManifest_Generation", "YES"),
            ("INFOPLIST_KEY_UIApplicationSupportsIndirectInputEvents", "YES"),
            ("INFOPLIST_KEY_UILaunchScreen_Generation", "YES"),
            ("INFOPLIST_KEY_UIStatusBarStyle", "UIStatusBarStyleLightContent"),
            ("INFOPLIST_KEY_UIUserInterfaceStyle", "Dark"),
            ("INFOPLIST_KEY_UISupportedInterfaceOrientations", "UIInterfaceOrientationPortrait"),
            (
                "INFOPLIST_KEY_UISupportedInterfaceOrientations_iPad",
                '"UIInterfaceOrientationPortrait UIInterfaceOrientationPortraitUpsideDown UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight"',
            ),
            ("LD_RUNPATH_SEARCH_PATHS", '("$(inherited)", "@executable_path/Frameworks",)'),
            ("MARKETING_VERSION", MARKETING_VERSION),
            ("PRODUCT_BUNDLE_IDENTIFIER", PRODUCT_BUNDLE_ID),
            ('"PRODUCT_NAME"', '"$(TARGET_NAME)"'),
            ("SUPPORTS_MACCATALYST", "NO"),
            ("SWIFT_VERSION", "5.0"),
            ("TARGETED_DEVICE_FAMILY", '"1,2"'),
        ]

        lines = ["/* Begin XCBuildConfiguration section */"]
        for name, settings in (("Debug", debug_project), ("Release", release_project)):
            cid = object_id("config", "project", name)
            lines.append(f"\t\t{cid} /* {name} */ = {{")
            lines.append("\t\t\tisa = XCBuildConfiguration;")
            lines.append("\t\t\tbuildSettings = {")
            for key, value in settings:
                lines.append(f"\t\t\t\t{key} = {value};")
            lines.append("\t\t\t};")
            lines.append(f"\t\t\tname = {name};")
            lines.append("\t\t};")
        for name in ("Debug", "Release"):
            cid = object_id("config", "target", name)
            lines.append(f"\t\t{cid} /* {name} */ = {{")
            lines.append("\t\t\tisa = XCBuildConfiguration;")
            lines.append("\t\t\tbuildSettings = {")
            for key, value in target_common:
                lines.append(f"\t\t\t\t{key} = {value};")
            lines.append("\t\t\t};")
            lines.append(f"\t\t\tname = {name};")
            lines.append("\t\t};")
        lines.append("/* End XCBuildConfiguration section */")
        return "\n".join(lines)

    def xc_configuration_list(self) -> str:
        return "\n".join([
            "/* Begin XCConfigurationList section */",
            f"\t\t{object_id('configlist', 'project')} /* Build configuration list for PBXProject \"Yomi\" */ = {{",
            "\t\t\tisa = XCConfigurationList;",
            "\t\t\tbuildConfigurations = (",
            f"\t\t\t\t{object_id('config', 'project', 'Debug')} /* Debug */,",
            f"\t\t\t\t{object_id('config', 'project', 'Release')} /* Release */,",
            "\t\t\t);",
            "\t\t\tdefaultConfigurationIsVisible = 0;",
            "\t\t\tdefaultConfigurationName = Release;",
            "\t\t};",
            f"\t\t{object_id('configlist', 'target')} /* Build configuration list for PBXNativeTarget \"Yomi\" */ = {{",
            "\t\t\tisa = XCConfigurationList;",
            "\t\t\tbuildConfigurations = (",
            f"\t\t\t\t{object_id('config', 'target', 'Debug')} /* Debug */,",
            f"\t\t\t\t{object_id('config', 'target', 'Release')} /* Release */,",
            "\t\t\t);",
            "\t\t\tdefaultConfigurationIsVisible = 0;",
            "\t\t\tdefaultConfigurationName = Release;",
            "\t\t};",
            "/* End XCConfigurationList section */",
        ])

    def package_references(self) -> str:
        kit_product = object_id("productdep", "YomiKit")
        return "\n".join([
            "/* Begin XCLocalSwiftPackageReference section */",
            f"\t\t{object_id('packageref', 'YomiKit')} /* XCLocalSwiftPackageReference \"YomiKit\" */ = {{",
            "\t\t\tisa = XCLocalSwiftPackageReference;",
            "\t\t\trelativePath = YomiKit;",
            "\t\t};",
            "/* End XCLocalSwiftPackageReference section */",
            "",
            "/* Begin XCSwiftPackageProductDependency section */",
            f"\t\t{kit_product} /* YomiKit */ = {{",
            "\t\t\tisa = XCSwiftPackageProductDependency;",
            "\t\t\tproductName = YomiKit;",
            "\t\t};",
            "/* End XCSwiftPackageProductDependency section */",
        ])

    def render(self) -> str:
        sections = [
            self.pbx_build_file(),
            self.pbx_file_reference(),
            self.pbx_frameworks(),
            self.pbx_group(),
            self.pbx_native_target(),
            self.pbx_project(),
            self.pbx_resources(),
            self.pbx_sources(),
            self.xc_build_configuration(),
            self.xc_configuration_list(),
            self.package_references(),
        ]
        return "\n\n".join(sections)

    def full_document(self) -> str:
        body = self.render()
        return (
            "// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tclasses = {\n\t};\n"
            "\tobjectVersion = 60;\n\tobjects = {\n\n" + body + "\n\t};\n"
            f"\trootObject = {object_id('project', 'root')} /* Project object */;\n}}\n"
        )


def verify(document: str) -> list[str]:
    """Structural sanity checks: balanced braces, referenced ids all defined."""
    problems = []
    if document.count("{") != document.count("}"):
        problems.append("unbalanced braces")
    if document.count("(") != document.count(")"):
        problems.append("unbalanced parentheses")

    defined = set()
    import re

    for match in re.finditer(r"^\t\t([0-9A-F]{24}) ", document, re.MULTILINE):
        defined.add(match.group(1))
    referenced = set(re.findall(r"\b([0-9A-F]{24})\b", document))
    missing = referenced - defined
    if missing:
        problems.append(f"referenced but undefined object ids: {sorted(missing)[:5]}")

    # Every file reference must appear in exactly one group's children list,
    # otherwise Xcode resolves its path relative to the project root.
    grouped = set()
    for block in re.finditer(r"children = \((.*?)\);", document, re.DOTALL):
        grouped.update(re.findall(r"\b([0-9A-F]{24})\b", block.group(1)))
    file_refs = set(
        re.findall(
            r"^\t\t([0-9A-F]{24}) /\* .*? \*/ = \{isa = PBXFileReference;",
            document,
            re.MULTILINE,
        )
    )
    ungrouped = file_refs - grouped
    if ungrouped:
        problems.append(f"file references not in any group: {sorted(ungrouped)[:5]}")

    for required in ("PBXNativeTarget", "PBXProject", "PBXSourcesBuildPhase",
                     "PBXResourcesBuildPhase", "XCConfigurationList",
                     "XCLocalSwiftPackageReference", "XCSwiftPackageProductDependency"):
        if required not in document:
            problems.append(f"missing section {required}")
    return problems


def scheme_document() -> str:
    target_id = object_id("target", "Yomi")
    reference = (
        'BuildableIdentifier="primary"\n'
        f'               BlueprintIdentifier="{target_id}"\n'
        '               BuildableName="Yomi.app"\n'
        '               BlueprintName="Yomi"\n'
        '               ReferencedContainer="container:Yomi.xcodeproj"'
    )
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<Scheme
   LastUpgradeVersion = "1500"
   version = "1.7">
   <BuildAction
      parallelizeBuildables = "YES"
      buildImplicitDependencies = "YES">
      <BuildActionEntries>
         <BuildActionEntry
            buildForTesting = "YES"
            buildForRunning = "YES"
            buildForProfiling = "YES"
            buildForArchiving = "YES"
            buildForAnalyzing = "YES">
            <BuildableReference
               {reference}>
            </BuildableReference>
         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <TestAction
      buildConfiguration = "Debug"
      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
      shouldUseLaunchSchemeArgsEnv = "YES">
      <Testables>
      </Testables>
   </TestAction>
   <LaunchAction
      buildConfiguration = "Debug"
      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
      launchStyle = "0"
      useCustomWorkingDirectory = "NO"
      ignoresPersistentStateOnLaunch = "NO"
      debugDocumentVersioning = "YES"
      debugServiceExtension = "internal"
      allowLocationSimulation = "YES">
      <BuildableProductRunnable
         runnableDebuggingMode = "0">
         <BuildableReference
            {reference}>
         </BuildableReference>
      </BuildableProductRunnable>
   </LaunchAction>
   <ProfileAction
      buildConfiguration = "Release"
      shouldUseLaunchSchemeArgsEnv = "YES"
      savedToolIdentifier = ""
      useCustomWorkingDirectory = "NO"
      debugDocumentVersioning = "YES">
      <BuildableProductRunnable
         runnableDebuggingMode = "0">
         <BuildableReference
            {reference}>
         </BuildableReference>
      </BuildableProductRunnable>
   </ProfileAction>
   <AnalyzeAction
      buildConfiguration = "Debug">
   </AnalyzeAction>
   <ArchiveAction
      buildConfiguration = "Release"
      revealArchiveInOrganizer = "YES">
   </ArchiveAction>
</Scheme>
"""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="verify without writing")
    args = parser.parse_args()

    builder = ProjectBuilder()
    if not builder.swift_files:
        print("error: no Swift sources found in YomiApp", file=sys.stderr)
        return 1
    document = builder.full_document()

    problems = verify(document)
    if problems:
        for problem in problems:
            print(f"verify: {problem}", file=sys.stderr)
        return 1

    if args.check:
        if not os.path.isfile(PROJECT_FILE):
            print("check: project file missing", file=sys.stderr)
            return 1
        with open(PROJECT_FILE, "r", encoding="utf-8") as handle:
            current = handle.read()
        if current != document:
            print("check: project file is stale — re-run generate_xcodeproj.py", file=sys.stderr)
            return 1
        if not os.path.isfile(SCHEME_FILE):
            print("check: shared scheme missing", file=sys.stderr)
            return 1
        with open(SCHEME_FILE, "r", encoding="utf-8") as handle:
            if handle.read() != scheme_document():
                print("check: shared scheme is stale — re-run generate_xcodeproj.py", file=sys.stderr)
                return 1
        print(f"check: OK ({len(builder.swift_files)} sources, {len(builder.resource_files)} resources)")
        return 0

    os.makedirs(PROJECT_DIR, exist_ok=True)
    with open(PROJECT_FILE, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(document)
    os.makedirs(SCHEME_DIR, exist_ok=True)
    with open(SCHEME_FILE, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(scheme_document())
    print(
        f"wrote {PROJECT_FILE} ({len(builder.swift_files)} sources, "
        f"{len(builder.resource_files)} resources)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
