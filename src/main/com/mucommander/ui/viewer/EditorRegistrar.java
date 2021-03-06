/*
 * This file is part of muCommander, http://www.mucommander.com
 * Copyright (C) 2002-2012 Maxence Bernard
 *
 * muCommander is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * muCommander is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


package com.mucommander.ui.viewer;

import java.awt.Frame;
import java.awt.Image;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.mucommander.commons.file.AbstractFile;
import com.mucommander.commons.file.FileProtocols;
import com.mucommander.commons.runtime.OsFamily;
import com.mucommander.commons.runtime.OsVersion;
import com.mucommander.utils.text.Translator;
import com.mucommander.ui.dialog.QuestionDialog;
import com.mucommander.ui.main.MainFrame;
import com.mucommander.ui.main.WindowManager;
import com.mucommander.ui.viewer.text.TextEditor;
import org.jetbrains.annotations.Nullable;

/**
 * EditorRegistrar maintains a list of registered file editors and provides methods to dynamically register file editors
 * and create appropriate FileEditor (Panel) and EditorFrame (Window) instances for a given AbstractFile.
 *
 * @author Maxence Bernard
 */
public class EditorRegistrar {
	
    /** List of registered file editors */ 
    private final static List<EditorFactory> editorFactories = new ArrayList<>();

    static {
        registerFileEditor(new com.mucommander.ui.viewer.text.TextFactory());
    }

    /**
     * Registers a FileEditor.
     * @param factory file editor factory to register.
     */
    private static void registerFileEditor(EditorFactory factory) {
        editorFactories.add(factory);
    }

    /**
     * Creates and returns an EditorFrame to start viewing the given file. The EditorFrame will be monitored
     * so that if it is the last window on screen when it is closed by the user, it will trigger the shutdown sequence.
     *
     * @param mainFrame the parent MainFrame instance
     * @param file the file that will be displayed by the returned EditorFrame 
     * @param icon editor frame's icon.
     * @param createListener postponed action
     */
    public static void createEditorFrame(MainFrame mainFrame, AbstractFile file, Image icon, FileFrameCreateListener createListener) {
        // Check if this file is already opened
        if (showEditorIfAlreadyOpen(file, createListener)) {
            return;
        }
        new FilePreloadWorker(file, mainFrame, () -> {
            EditorFrame frame = new EditorFrame(mainFrame, file, icon);
            // Use new Window decorations introduced in Mac OS X 10.5 (Leopard)
            if (OsFamily.MAC_OS_X.isCurrent() && OsVersion.MAC_OS_X_10_5.isCurrentOrHigher()) {
                // Displays the document icon in the window title bar, works only for local files
                if (file.getURL().getScheme().equals(FileProtocols.FILE)) {
                    frame.getRootPane().putClientProperty("Window.documentFile", file.getUnderlyingFileObject());
                }
            }
            // WindowManager will listen to window closed events to trigger shutdown sequence
            // if it is the last window visible
            frame.addWindowListener(WindowManager.getInstance());
            if (createListener != null) {
                createListener.onCreate(frame);
            }
        }).execute();
    }

    private static boolean showEditorIfAlreadyOpen(AbstractFile file, FileFrameCreateListener createListener) {
        for (FileViewersList.FileRecord fr: FileViewersList.getFiles()) {
            if (fr.fileName.equals(file.getAbsolutePath()) && fr.viewerClass != null) {
                Class viewerClass = fr.viewerClass;
                if (viewerClass.equals(TextEditor.class)) {
                    FileFrame openedFrame = fr.fileFrameRef.get();
                    if (openedFrame != null) {
                        openedFrame.toFront();
                        if (createListener != null) {
                            createListener.onCreate(openedFrame);
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public static void createEditorFrame(MainFrame mainFrame, AbstractFile file, Image icon) {
        createEditorFrame(mainFrame, file, icon, null);
    }

    
    /**
     * Creates and returns an appropriate FileEditor for the given file type.
     *
     * @param file the file that will be displayed by the returned FileEditor
     * @param frame the frame in which the FileEditor is shown
     * @return the created FileEditor, or null if no suitable editor was found
     * @throws UserCancelledException if the user has been asked to confirm the operation and canceled
     */
    static FileEditor createFileEditor(AbstractFile file, EditorFrame frame) throws UserCancelledException {
        FileEditor editor = createFileEditor(file);

        if (editor != null) {
            editor.setFrame(frame);
        }
    	
    	return editor;
    }

    @Nullable
    private static FileEditor createFileEditor(AbstractFile file) throws UserCancelledException {
        for (EditorFactory factory : editorFactories) {
            try {
                if (factory.canEditFile(file)) {
                    return factory.createFileEditor();
                }
            } catch (WarnUserException e) {
                showQuestionDialog(file, e);

                // User confirmed the operation
                return factory.createFileEditor();
            }
        }
        return null;
    }

    private static void showQuestionDialog(AbstractFile file, WarnUserException e) throws UserCancelledException {
        QuestionDialog dialog = new QuestionDialog((Frame)null,
                Translator.get("warning"), Translator.get(e.getMessage()), null,
                new String[] {Translator.get("file_editor.open_anyway"), Translator.get("cancel")},
                new int[]  {0, 1},
                0);

        int ret = dialog.getActionValue();
        if (ret == 1 || ret == -1) {   // User canceled the operation
            try {
                file.closePushbackInputStream();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            throw new UserCancelledException();
        }
    }

    public static List<EditorFactory> getAllEditors(AbstractFile file) {
        List<EditorFactory> result = new ArrayList<>();
        for (EditorFactory factory : editorFactories) {
            try {
                if (!factory.canEditFile(file)) {
                    continue;
                }
            } catch (WarnUserException ignore) {}
            result.add(factory);
        }
        return result;
    }
}
