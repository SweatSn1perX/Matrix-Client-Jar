# Implementation Plan: Click GUI Overhaul

This plan outlines the steps to refactor the current window-based Click GUI into a modern, two-pane layout with a scrollable module list on the left and a settings configuration panel on the right.

## Objectives
- Replace the multi-window category system with a single unified screen.
- Implement a scrollable sidebar on the left for all modules.
- Create a dynamic settings panel on the right that updates based on the selected module.
- Maintain existing functionality (toggling modules, keybinds, sliders, color picker).

## Proposed Architecture

### 1. `ClickGuiScreen.java` Refactor
The `ClickGuiScreen` will be the primary screen. It will no longer manage multiple `Window` objects. Instead, it will manage:
- `List<Module> allModules`: A flat list of all registered modules.
- `Module selectedModule`: The module currently being edited in the right panel.
- `double scrollOffset`: For scrolling through the sidebar.
- `Scissor Rendering`: To ensure the module list doesn't bleed out of its designated area.

### 2. Layout, Colors & Aesthetics
- **Main Container**: 400x250 pixels (centered).
- **Sidebar (Left)**: `0xFF2A2A2A` (Light Gray/Steel) - Modern, clean sidebar for module selection.
- **Settings Panel (Right)**: `0xFF161616` (Dark Gray/Obsidian) - Deep contrast for clarity.
- **Accent**: `ClickGui.accentColor` - Used for selection indicators, sliders, and active toggles.
- **Vibe**: Sleek, premium, and minimal. Thin 1px borders, subtle hover transitions, and clearly defined regions.

### 3. Logic Components

#### A. Sidebar Rendering (Light Gray)
- Render the module list with a vertical separator.
- Loop through `allModules`.
- Selected modules should have a subtle background highlight and a small accent bar.
- Handle mouse clicks to update `selectedModule`.

#### B. Settings Panel Rendering (Dark Gray)
- If `selectedModule` is not null:
    - Render in the dark gray region to separate it visually from the list.
    - Draw "Module Name" and "Description" with high-contrast text.
    - Render settings using refined UI components (rounded toggle boxes, slim sliders).

#### C. Input Handling
- **Mouse Click**: 
    - Determine if the click was in the sidebar (to select) or settings panel (to adjust settings).
    - Left-click on sidebar = select + toggle (or just select, based on preference).
    - Left-click on settings = toggle setting or start dragging slider.
- **Mouse Drag**:
    - Update sliders in the settings panel.
- **Mouse Scroll**:
    - Update `scrollOffset` for the sidebar.

## Step-by-Step Walkthrough

### Step 1: Data Preparation
- Modify `ClickGuiScreen` to store a `List<Module>` containing all modules from `Modules.get().getAll()`.
- Sort them alphabetically or by category if desired, but they will be displayed as a single list.

### Step 2: Main Render Loop
- Draw a main container rectangle with a border.
- Split the container into two vertical sections using a separator line.

### Step 3: Implement Sidebar
- Use `context.enableScissor` to clip rendering to the sidebar area.
- Add `scrollOffset` logic to the vertical position of each module entry.
- Draw a highlight rectangle for the `selectedModule`.
- Implement `mouseScrolled` to modify `scrollOffset`.

### Step 4: Implement Settings Panel logic
- Create a helper method `renderSettings(DrawContext context, Module module, int mouseX, int mouseY)` inside `ClickGuiScreen`.
- Copy logic from `ModuleSettingsScreen.java` into this method, adjusting coordinates to be relative to the right panel.

### Step 5: Input Logic Merging
- Move click/drag logic from `ModuleSettingsScreen` and the old `Window` class into `ClickGuiScreen`.
- Ensure coordinates are correctly offset to the panel's position on screen.

### Step 6: Cleanup
- Delete `ModuleSettingsScreen.java` (it is no longer needed as a separate screen).
- Update `ClickGui` module's toggle logic to only open `ClickGuiScreen`.

## Potential Gotchas
- **Scissoring**: Ensure `context.disableScissor()` is called after rendering the sidebar.
- **Coordinate Offsets**: Be careful when calculating hitboxes for sliders in the settings panel, as they will now be relative to the main panel's `x` and `y`.
- **Z-Indexing**: Ensure tooltips (if any) are rendered last, above everything else.
