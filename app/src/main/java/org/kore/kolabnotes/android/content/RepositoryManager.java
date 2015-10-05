package org.kore.kolabnotes.android.content;

import android.content.Context;
import android.util.Log;

import org.kore.kolab.notes.Note;
import org.kore.kolab.notes.Notebook;
import org.kore.kolab.notes.NotesRepository;
import org.kore.kolab.notes.Tag;
import org.kore.kolab.notes.imap.ImapNotesRepository;
import org.kore.kolab.notes.imap.RemoteTags;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * This class syncs the database with data from a given repository
 *
 * Created by koni on 18.04.15.
 */
public class RepositoryManager {

    private final NoteTagRepository noteTagRepository;
    private final TagRepository tagRepository;
    private final NoteRepository noteRepository;
    private final NotebookRepository notebookRepository;
    private final ModificationRepository modificationRepository;

    private final ImapNotesRepository repo;

    public RepositoryManager(Context context, ImapNotesRepository repo) {
        this.noteTagRepository = new NoteTagRepository(context);
        this.tagRepository = new TagRepository(context);
        this.noteRepository = new NoteRepository(context);
        this.notebookRepository = new NotebookRepository(context);
        this.modificationRepository = new ModificationRepository(context);
        this.repo = repo;
    }

    public void sync(String email, String rootFolder){
        putLocalDataIntoRepository(email, rootFolder);
        cleanLocalData(email,rootFolder);
        putDataIntoDB(email,rootFolder);
        modificationRepository.cleanAccount(email,rootFolder);
    }

    void putDataIntoDB(String email, String rootFolder){
        Collection<Notebook> notebooks = repo.getNotebooks();

        for(Notebook book : notebooks){
            notebookRepository.insert(email,rootFolder,book);

            for(Note note : book.getNotes()){
                noteRepository.insert(email,rootFolder,note,book.getIdentification().getUid());
            }
        }

        RemoteTags remoteTags = repo.getRemoteTags();
        List<String> localTags = tagRepository.getAll();

        for(RemoteTags.TagDetails detail : remoteTags.getTags()){
            String remoteName = detail.getTag().getName();
            if(!localTags.contains(remoteName)){
                tagRepository.insert(remoteName);
            }

            for(String noteUid : detail.getMembers()){
                noteTagRepository.insert(email,rootFolder,noteUid,remoteName);
            }
        }
    }

    void cleanLocalData(String email, String rootFolder){
        noteRepository.cleanAccount(email,rootFolder);
        noteTagRepository.cleanAccount(email,rootFolder);
    }

    private Notebook searchNotebookOfNote(NotesRepository repo, String noteUID){
        for(Notebook notebook : repo.getNotebooks()){
            if(notebook.getNote(noteUID) != null){
                return notebook;
            }
        }
        return null;
    }

    void putLocalDataIntoRepository(String email, String rootFolder){
        List<Note> localNotes = noteRepository.getAllForSync(email, rootFolder);

        List<Modification> deletedNbs = modificationRepository.getDeletions(email, rootFolder, Modification.Descriminator.NOTEBOOK);

        for(Note note : localNotes){
            Modification modification = modificationRepository.getUnique(email, rootFolder, note.getIdentification().getUid());

            if(modification != null){
                Notebook localNotebook = notebookRepository.getByUID(email, rootFolder, noteRepository.getUIDofNotebook(email, rootFolder, note.getIdentification().getUid()));
                Notebook remoteNotebook = repo.getNotebookBySummary(localNotebook.getSummary());

                if(remoteNotebook == null){
                    Log.d("localIntoRepository","Creating new notebook on server:"+localNotebook.getSummary());
                    remoteNotebook = repo.createNotebook(localNotebook.getIdentification().getUid(), localNotebook.getSummary());
                }

                if(ModificationRepository.ModificationType.INS.equals(modification.getType())){
                    Log.d("localIntoRepository","Creating new note:"+note);
                    remoteNotebook.addNote(note);
                }else{
                    Note remoteNote = remoteNotebook.getNote(note.getIdentification().getUid());

                    //if the notebook of the note was changed locally
                    if(remoteNote == null){
                        final Notebook notebook = searchNotebookOfNote(repo, note.getIdentification().getUid());

                        //the note exists in another notebook
                        if(notebook != null){
                            notebook.deleteNote(note.getIdentification().getUid());
                            remoteNotebook.addNote(note);
                            remoteNote = note;
                        }
                    }

                    //Just do something, if the remote note is not deleted or was not updated after local note
                    if(remoteNote != null && note.getAuditInformation().getLastModificationDate().after(remoteNote.getAuditInformation().getLastModificationDate())){

                        Log.d("localIntoRepository","Updating note:"+note);

                        remoteNote.setClassification(note.getClassification());
                        remoteNote.setDescription(note.getDescription());
                        remoteNote.setSummary(note.getSummary());

                        Set<Tag> remoteCategories = remoteNote.getCategories();

                        remoteNote.removeCategories(remoteCategories.toArray(new Tag[remoteCategories.size()]));

                        Set<Tag> localCategories = note.getCategories();
                        remoteNote.addCategories(localCategories.toArray(new Tag[localCategories.size()]));
                        remoteNote.setColor(note.getColor());
                        remoteNote.getAuditInformation().setLastModificationDate(note.getAuditInformation().getLastModificationDate().getTime());
                        remoteNote.getAuditInformation().setCreationDate(note.getAuditInformation().getCreationDate().getTime());
                    }
                }
            }else{
                //Fill the unchanged, unloaded notes, so that in the later step, everything can be replaced in the local repo with data from the remote repo
                repo.fillUnloadedNote(note);
            }
        }

        List<Modification> deletions = modificationRepository.getDeletions(email, rootFolder, Modification.Descriminator.NOTE);
        for(Modification deletion : deletions){
            Note remoteNote = repo.getNote(deletion.getUid());

            if(remoteNote != null && deletion.getModificationDate().after(remoteNote.getAuditInformation().getLastModificationDate())){
                Log.d("localIntoRepository","Deleting note:"+remoteNote);
                Notebook localNotebook = notebookRepository.getByUID(email, rootFolder, deletion.getUidNotebook());
                repo.getNotebookBySummary(localNotebook.getSummary()).deleteNote(deletion.getUid());
            }
        }

        for(Modification deletion : deletedNbs){

            //Because Notebooks are IMAP-Folders, there is no persistent UID, so in the UidNotebook field, ist the summary of the notebook, which identifies the notebook persistent
            Notebook toDelete = repo.getNotebookBySummary(deletion.getUidNotebook());

            //Also there is no modification date, so if there is a notebook (folder) with this name, delete it
            if(toDelete != null){
                Log.d("localIntoRepository","Deleting notebook:"+toDelete.getSummary());
                repo.deleteNotebook(toDelete.getIdentification().getUid());
            }
        }
    }
}
