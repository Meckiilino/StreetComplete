package de.westnordost.osmagent.data.osmnotes;

import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;

import de.westnordost.osmagent.data.QuestStatus;
import de.westnordost.osmapi.common.errors.OsmConflictException;
import de.westnordost.osmapi.map.data.LatLon;
import de.westnordost.osmapi.notes.Note;
import de.westnordost.osmapi.notes.NotesDao;

// TODO test case
public class OsmNoteQuestChangesUpload
{
	private static final String TAG = "NoteCommentUpload";

	private final NotesDao osmDao;
	private final OsmNoteQuestDao questDB;
	private final NoteDao noteDB;

	@Inject public OsmNoteQuestChangesUpload(NotesDao osmDao, OsmNoteQuestDao questDB, NoteDao noteDB)
	{
		this.osmDao = osmDao;
		this.questDB = questDB;
		this.noteDB = noteDB;
	}

	public void upload(AtomicBoolean cancelState)
	{
		int created = 0, obsolete = 0;
		for(OsmNoteQuest quest : questDB.getAll(null, QuestStatus.ANSWERED))
		{
			if(cancelState.get()) break;

			if(uploadNoteChanges(quest) != null)
			{
				created++;
			}
			else
			{
				obsolete++;
			}
		}
		String logMsg = "Successfully commented on " + created + " notes";
		if(obsolete > 0)
		{
			logMsg += " but dropped " + obsolete + " comments because the notes have already been closed";
		}
		Log.i(TAG, logMsg);
	}

	Note uploadNoteChanges(OsmNoteQuest quest)
	{
		String text = quest.getComment();

		try
		{
			Note newNote = osmDao.comment(quest.getNote().id, text);

			/* Unlike OSM quests, note quests are not deleted when the user contributed to it
			   but must remain in the database with the status HIDDEN as long as they are not
			   solved. The reason is because as long as a note is unsolved, the problem at that
			   position persists and thus it should still block other quests to be created.
			   (Reminder: Note quests block other quests)
			  */
			// so, not this: questDB.delete(quest.getId());
			quest.setStatus(QuestStatus.HIDDEN);
			questDB.update(quest);
			noteDB.put(quest.getNote());

			return newNote;
		}
		catch(OsmConflictException e)
		{
			// someone else already closed the note -> our contribution is probably worthless. Delete
			questDB.delete(quest.getId());
			noteDB.delete(quest.getNote().id);

			Log.v(TAG, "Dropped the comment " + getNoteQuestStringForLog(quest) +
					" because the note has already been closed");

			return null;
		}
	}

	private static String getNoteQuestStringForLog(OsmNoteQuest n)
	{
		LatLon pos = n.getMarkerLocation();
		return "\"" + n.getComment() + "\" at " + pos.getLatitude() + ", " + pos.getLongitude();
	}

}
