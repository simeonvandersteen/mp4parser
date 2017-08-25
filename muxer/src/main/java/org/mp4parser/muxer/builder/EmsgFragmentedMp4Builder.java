package org.mp4parser.muxer.builder;

import org.mp4parser.Box;
import org.mp4parser.Container;
import org.mp4parser.ParsableBox;
import org.mp4parser.boxes.KindBox;
import org.mp4parser.boxes.iso14496.part12.*;
import org.mp4parser.boxes.microsoft.TfxdBox;
import org.mp4parser.boxes.samplegrouping.GroupEntry;
import org.mp4parser.boxes.samplegrouping.SampleGroupDescriptionBox;
import org.mp4parser.boxes.samplegrouping.SampleToGroupBox;
import org.mp4parser.muxer.Movie;
import org.mp4parser.muxer.Track;

import java.util.*;

import static org.mp4parser.tools.CastUtils.l2i;

public class EmsgFragmentedMp4Builder extends FragmentedMp4Builder {
    private long offset = 0L;

    // Dumb down the original version and always just add an nmhd
    protected ParsableBox createMinf(Track track, Movie movie) {
        MediaInformationBox minf = new MediaInformationBox();
        minf.addBox(new NullMediaHeaderBox());
        minf.addBox(createDinf(movie, track));
        minf.addBox(createStbl(movie, track));
        return minf;
    }

    // Exten the original to add the user metadata
    protected ParsableBox createTrak(Track track, Movie movie) {
        TrackBox trackBox = new TrackBox();
        trackBox.addBox(createTkhd(movie, track));

        UserDataBox userDataBox = new UserDataBox();
        KindBox kindBox = new KindBox("urn:mpeg:dash:event:2012", "1");
        userDataBox = new UserDataBox();
        userDataBox.addBox(kindBox);
        trackBox.addBox(userDataBox);

        Box edts = createEdts(track, movie);
        if (edts != null) {
            trackBox.addBox(edts);
        }
        trackBox.addBox(createMdia(track, movie));
        return trackBox;
    }

    protected void createTfdt(long startSample, Track track, TrackFragmentBox parent) {
        TrackFragmentBaseMediaDecodeTimeBox tfdt = new TrackFragmentBaseMediaDecodeTimeBox();
        tfdt.setVersion(1);
        tfdt.setBaseMediaDecodeTime(getBaseMediaDecodeTime(startSample, track));
        parent.addBox(tfdt);
    }

    // The original version doesn't add a tfxd
    @Override
    protected void createTraf(long startSample, long endSample, Track track, int sequenceNumber, MovieFragmentBox parent) {
        TrackFragmentBox traf = new TrackFragmentBox();
        parent.addBox(traf);
        createTfhd(startSample, endSample, track, sequenceNumber, traf);
        createTfdt(startSample, track, traf);
        createTrun(startSample, endSample, track, sequenceNumber, traf);

        Map<String, List<GroupEntry>> groupEntryFamilies = new HashMap<String, List<GroupEntry>>();
        for (Map.Entry<GroupEntry, long[]> sg : track.getSampleGroups().entrySet()) {
            String type = sg.getKey().getType();
            List<GroupEntry> groupEntries = groupEntryFamilies.get(type);
            if (groupEntries == null) {
                groupEntries = new ArrayList<GroupEntry>();
                groupEntryFamilies.put(type, groupEntries);
            }
            groupEntries.add(sg.getKey());
        }


        for (Map.Entry<String, List<GroupEntry>> sg : groupEntryFamilies.entrySet()) {
            SampleGroupDescriptionBox sgpd = new SampleGroupDescriptionBox();
            String type = sg.getKey();
            sgpd.setGroupEntries(sg.getValue());
            sgpd.setGroupingType(type);
            SampleToGroupBox sbgp = new SampleToGroupBox();
            sbgp.setGroupingType(type);
            SampleToGroupBox.Entry last = null;
            for (int i = l2i(startSample - 1); i < l2i(endSample - 1); i++) {
                int index = 0;
                for (int j = 0; j < sg.getValue().size(); j++) {
                    GroupEntry groupEntry = sg.getValue().get(j);
                    long[] sampleNums = track.getSampleGroups().get(groupEntry);
                    if (Arrays.binarySearch(sampleNums, i) >= 0) {
                        index = j + 0x10001;
                    }
                }
                if (last == null || last.getGroupDescriptionIndex() != index) {
                    last = new SampleToGroupBox.Entry(1, index);
                    sbgp.getEntries().add(last);
                } else {
                    last.setSampleCount(last.getSampleCount() + 1);
                }
            }
            traf.addBox(sgpd);
            traf.addBox(sbgp);
        }

        createTfxd(startSample, track, traf);
    }

    protected void createTfxd(long startSample, Track track, TrackFragmentBox parent) {
        TfxdBox tfxd = new TfxdBox();
        tfxd.fragmentAbsoluteTime = getBaseMediaDecodeTime(startSample, track);
        tfxd.fragmentAbsoluteDuration = getSampleDuration(startSample, track);
        tfxd.setVersion(1);
        parent.addBox(tfxd);
    }

    protected ParsableBox createMoof(long startSample, long endSample, Track track, int sequenceNumber) {
        MovieFragmentBox moof = new MovieFragmentBox();
        createMfhd(startSample, endSample, track, sequenceNumber, moof);
        createTraf(startSample, endSample, track, sequenceNumber, moof);

        TrackRunBox firstTrun = moof.getTrackRunBoxes().get(0);
        firstTrun.setDataOffset(1); // dummy to make size correct
        firstTrun.setDataOffset((int) (8 + moof.getSize())); // mdat header + moof size

        return moof;
    }

    // CodeShop require no Tfra boxes in the Mfra
    @Override
    protected ParsableBox createMfra(Movie movie, Container isoFile) {
        MovieFragmentRandomAccessBox mfra = new MovieFragmentRandomAccessBox();
        MovieFragmentRandomAccessOffsetBox mfro = new MovieFragmentRandomAccessOffsetBox();
        mfra.addBox(mfro);
        mfro.setMfraSize(mfra.getSize());
        return mfra;
    }

    protected long getBaseMediaDecodeTime(long startSample, Track track) {
        long startTime = offset;
        long[] times = track.getSampleDurations();
        for (int i = 1; i < startSample; i++) {
            startTime += times[i - 1];
        }
        return startTime;
    }

    protected long getSampleDuration(long startSample, Track track) {
        long times[] = track.getSampleDurations();
        return times[(int) startSample - 1];
    }

    protected void setOffset(long offset) {
        this.offset = offset;
    }
}
