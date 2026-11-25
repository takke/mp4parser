/*
 * Copyright 2012 Sebastian Annies, Hamburg
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.googlecode.mp4parser.authoring.tracks;

import com.coremedia.iso.boxes.*;
import com.coremedia.iso.boxes.sampleentry.AudioSampleEntry;
import com.coremedia.iso.boxes.sampleentry.SampleEntry;
import com.coremedia.iso.boxes.sampleentry.VisualSampleEntry;
import com.googlecode.mp4parser.authoring.AbstractTrack;
import com.googlecode.mp4parser.authoring.Sample;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.TrackMetaData;
import com.googlecode.mp4parser.boxes.mp4.AbstractDescriptorBox;
import com.googlecode.mp4parser.boxes.mp4.ESDescriptorBox;
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.BaseDescriptor;
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.DecoderConfigDescriptor;
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.ESDescriptor;
import com.googlecode.mp4parser.util.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.*;

/**
 * Appends two or more <code>Tracks</code> of the same type. No only that the type must be equal
 * also the decoder settings must be the same.
 */
public class AppendTrack extends AbstractTrack {
    private static Logger LOG = Logger.getLogger(AppendTrack.class);
    Track[] tracks;
    SampleDescriptionBox stsd;
    List<Sample> lists;
    long[] decodingTimes;

    public AppendTrack(Track... tracks) throws IOException {
        super(appendTracknames(tracks));
        this.tracks = tracks;

        if (hasUnreadableSampleEntry(tracks)) {
            // SampleEntry として読めない sample entry(未対応コーデックの vp09 / av01 / Opus や、未知のメタデータトラック等。
            // UnknownBox として読まれる)を持つトラックは、sample entry の中身を解釈してマージできない。
            // 全トラックの stsd がバイト単位で同一(同じ元トラックから切り出した区間の連結など)なら、元の stsd をそのまま使う。
            // (以前は下の getBoxes(SampleEntry.class).get(0) が空リストに対して IndexOutOfBoundsException("Index: 0") になっていた)
            stsd = requireIdenticalStsds(tracks);
        } else {
            for (Track track : tracks) {

                if (stsd == null) {
                    stsd = new SampleDescriptionBox();
                    stsd.addBox(track.getSampleDescriptionBox().getBoxes(SampleEntry.class).get(0));
                } else {
                    stsd = mergeStsds(stsd, track.getSampleDescriptionBox());

                }
            }
        }
        lists = new ArrayList<Sample>();

        for (Track track : tracks) {
            //System.err.println("Track " + track + " is about to be appended");
            lists.addAll(track.getSamples());
        }

        int numSamples = 0;
        for (Track track : tracks) {
            numSamples += track.getSampleDurations().length;
        }
        decodingTimes = new long[numSamples];
        int index = 0;
        // should use system arraycopy but this works too (yes it's slow ...)
        for (Track track : tracks) {
            long[] durs = track.getSampleDurations();
            System.arraycopy(durs, 0, decodingTimes, index, durs.length);
            index += durs.length;
        }
    }

    /**
     * SampleEntry として読めない sample entry しか持たない stsd のトラックがあるかどうか
     */
    private static boolean hasUnreadableSampleEntry(Track[] tracks) {
        for (Track track : tracks) {
            SampleDescriptionBox trackStsd = track.getSampleDescriptionBox();
            if (trackStsd != null && trackStsd.getBoxes(SampleEntry.class).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 全トラックの stsd がバイト単位で同一であることを確認し、先頭トラックの stsd を返す
     *
     * @throws IOException stsd が異なる場合(sample entry を解釈できないためマージできない)
     */
    private static SampleDescriptionBox requireIdenticalStsds(Track[] tracks) throws IOException {
        SampleDescriptionBox first = tracks[0].getSampleDescriptionBox();
        if (first == null) {
            throw new IOException("トラックを連結できません(先頭のトラックに stsd がありません)");
        }
        byte[] firstBytes = toBytes(first);
        for (int i = 1; i < tracks.length; i++) {
            SampleDescriptionBox other = tracks[i].getSampleDescriptionBox();
            if (other != first && (other == null || !Arrays.equals(firstBytes, toBytes(other)))) {
                throw new IOException("未対応の形式(sample entry)のトラックは、形式の設定が異なると連結できません: ["
                        + describeSampleEntries(first) + "] と [" + describeSampleEntries(other) + "]");
            }
        }
        return first;
    }

    private static byte[] toBytes(SampleDescriptionBox box) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        box.getBox(Channels.newChannel(baos));
        return baos.toByteArray();
    }

    /**
     * エラーメッセージ用に stsd 内の sample entry の種別(4CC)を列挙する
     */
    private static String describeSampleEntries(SampleDescriptionBox box) {
        if (box == null) {
            return "stsd なし";
        }
        StringBuilder sb = new StringBuilder();
        for (Box entry : box.getBoxes()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getType());
        }
        return sb.toString();
    }

    public static String appendTracknames(Track... tracks) {
        String name = "";
        for (Track track : tracks) {
            name += track.getName() + " + ";
        }
        return name.substring(0, name.length() - 3);
    }

    public void close() throws IOException {
        for (Track track : tracks) {
            track.close();
        }
    }

    private SampleDescriptionBox mergeStsds(SampleDescriptionBox stsd1, SampleDescriptionBox stsd2) throws IOException {
        ByteArrayOutputStream curBaos = new ByteArrayOutputStream();
        ByteArrayOutputStream refBaos = new ByteArrayOutputStream();
        try {
            stsd1.getBox(Channels.newChannel(curBaos));
            stsd2.getBox(Channels.newChannel(refBaos));
        } catch (IOException e) {
            LOG.logError(e.getMessage());
            return null;
        }
        byte[] cur = curBaos.toByteArray();
        byte[] ref = refBaos.toByteArray();

        if (!Arrays.equals(ref, cur)) {
            SampleEntry se = mergeSampleEntry(stsd1.getBoxes(SampleEntry.class).get(0), stsd2.getBoxes(SampleEntry.class).get(0));
            if (se != null) {
                stsd1.setBoxes(Collections.<Box>singletonList(se));
            } else {
                throw new IOException("Cannot merge " + stsd1.getBoxes(SampleEntry.class).get(0) + " and " + stsd2.getBoxes(SampleEntry.class).get(0));
            }
        }
        return stsd1;
    }

    private SampleEntry mergeSampleEntry(SampleEntry se1, SampleEntry se2) {
        if (!se1.getType().equals(se2.getType())) {
            return null;
        } else if (se1 instanceof VisualSampleEntry && se2 instanceof VisualSampleEntry) {
            return mergeVisualSampleEntry((VisualSampleEntry) se1, (VisualSampleEntry) se2);
        } else if (se1 instanceof AudioSampleEntry && se2 instanceof AudioSampleEntry) {
            return mergeAudioSampleEntries((AudioSampleEntry) se1, (AudioSampleEntry) se2);
        } else {
            return null;
        }
    }

    private VisualSampleEntry mergeVisualSampleEntry(VisualSampleEntry vse1, VisualSampleEntry vse2) {
        VisualSampleEntry vse = new VisualSampleEntry();
        if (vse1.getHorizresolution() == vse2.getHorizresolution()) {
            vse.setHorizresolution(vse1.getHorizresolution());
        } else {
            LOG.logError("Horizontal Resolution differs");
            return null;
        }
        vse.setCompressorname(vse1.getCompressorname()); // ignore if they differ
        if (vse1.getDepth() == vse2.getDepth()) {
            vse.setDepth(vse1.getDepth());
        } else {
            LOG.logError("Depth differs");
            return null;
        }

        if (vse1.getFrameCount() == vse2.getFrameCount()) {
            vse.setFrameCount(vse1.getFrameCount());
        } else {
            LOG.logError("frame count differs");
            return null;
        }

        if (vse1.getHeight() == vse2.getHeight()) {
            vse.setHeight(vse1.getHeight());
        } else {
            LOG.logError("height differs");
            return null;
        }
        if (vse1.getWidth() == vse2.getWidth()) {
            vse.setWidth(vse1.getWidth());
        } else {
            LOG.logError("width differs");
            return null;
        }

        if (vse1.getVertresolution() == vse2.getVertresolution()) {
            vse.setVertresolution(vse1.getVertresolution());
        } else {
            LOG.logError("vert resolution differs");
            return null;
        }

        if (vse1.getHorizresolution() == vse2.getHorizresolution()) {
            vse.setHorizresolution(vse1.getHorizresolution());
        } else {
            LOG.logError("horizontal resolution differs");
            return null;
        }

        if (vse1.getBoxes().size() == vse2.getBoxes().size()) {
            Iterator<Box> bxs1 = vse1.getBoxes().iterator();
            Iterator<Box> bxs2 = vse2.getBoxes().iterator();
            while (bxs1.hasNext()) {
                Box cur1 = bxs1.next();
                Box cur2 = bxs2.next();
                ByteArrayOutputStream baos1 = new ByteArrayOutputStream();
                ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
                try {
                    cur1.getBox(Channels.newChannel(baos1));
                    cur2.getBox(Channels.newChannel(baos2));
                } catch (IOException e) {
                    LOG.logWarn(e.getMessage());
                    return null;
                }
                if (Arrays.equals(baos1.toByteArray(), baos2.toByteArray())) {
                    vse.addBox(cur1);
                } else {
                    if (cur1 instanceof AbstractDescriptorBox && cur2 instanceof AbstractDescriptorBox) {
                        BaseDescriptor esd = mergeDescriptors(((AbstractDescriptorBox) cur1).getDescriptor(), ((AbstractDescriptorBox) cur2).getDescriptor());
                        ((AbstractDescriptorBox) cur1).setDescriptor(esd);
                        vse.addBox(cur1);
                    }
                }
            }
        }
        return vse;
    }

    private AudioSampleEntry mergeAudioSampleEntries(AudioSampleEntry ase1, AudioSampleEntry ase2) {
        AudioSampleEntry ase = new AudioSampleEntry(ase2.getType());
        if (ase1.getBytesPerFrame() == ase2.getBytesPerFrame()) {
            ase.setBytesPerFrame(ase1.getBytesPerFrame());
        } else {
            LOG.logError("BytesPerFrame differ");
            return null;
        }
        if (ase1.getBytesPerPacket() == ase2.getBytesPerPacket()) {
            ase.setBytesPerPacket(ase1.getBytesPerPacket());
        } else {
            return null;
        }
        if (ase1.getBytesPerSample() == ase2.getBytesPerSample()) {
            ase.setBytesPerSample(ase1.getBytesPerSample());
        } else {
            LOG.logError("BytesPerSample differ");
            return null;
        }
        if (ase1.getChannelCount() == ase2.getChannelCount()) {
            ase.setChannelCount(ase1.getChannelCount());
        } else {
            return null;
        }
        if (ase1.getPacketSize() == ase2.getPacketSize()) {
            ase.setPacketSize(ase1.getPacketSize());
        } else {
            LOG.logError("ChannelCount differ");
            return null;
        }
        if (ase1.getCompressionId() == ase2.getCompressionId()) {
            ase.setCompressionId(ase1.getCompressionId());
        } else {
            return null;
        }
        if (ase1.getSampleRate() == ase2.getSampleRate()) {
            ase.setSampleRate(ase1.getSampleRate());
        } else {
            return null;
        }
        if (ase1.getSampleSize() == ase2.getSampleSize()) {
            ase.setSampleSize(ase1.getSampleSize());
        } else {
            return null;
        }
        if (ase1.getSamplesPerPacket() == ase2.getSamplesPerPacket()) {
            ase.setSamplesPerPacket(ase1.getSamplesPerPacket());
        } else {
            return null;
        }
        if (ase1.getSoundVersion() == ase2.getSoundVersion()) {
            ase.setSoundVersion(ase1.getSoundVersion());
        } else {
            return null;
        }
        if (Arrays.equals(ase1.getSoundVersion2Data(), ase2.getSoundVersion2Data())) {
            ase.setSoundVersion2Data(ase1.getSoundVersion2Data());
        } else {
            return null;
        }
        if (ase1.getBoxes().size() == ase2.getBoxes().size()) {
            Iterator<Box> bxs1 = ase1.getBoxes().iterator();
            Iterator<Box> bxs2 = ase2.getBoxes().iterator();
            while (bxs1.hasNext()) {
                Box cur1 = bxs1.next();
                Box cur2 = bxs2.next();
                ByteArrayOutputStream baos1 = new ByteArrayOutputStream();
                ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
                try {
                    cur1.getBox(Channels.newChannel(baos1));
                    cur2.getBox(Channels.newChannel(baos2));
                } catch (IOException e) {
                    LOG.logWarn(e.getMessage());
                    return null;
                }
                if (Arrays.equals(baos1.toByteArray(), baos2.toByteArray())) {
                    ase.addBox(cur1);
                } else {
                    if (ESDescriptorBox.TYPE.equals(cur1.getType()) && ESDescriptorBox.TYPE.equals(cur2.getType())) {
                        ESDescriptorBox esdsBox1 = (ESDescriptorBox) cur1;
                        ESDescriptorBox esdsBox2 = (ESDescriptorBox) cur2;
                        ESDescriptor esd = mergeDescriptors(esdsBox1.getEsDescriptor(), esdsBox2.getEsDescriptor());
                        esdsBox1.setDescriptor(esd);
                        ase.addBox(cur1);
                    }
                }
            }
        }
        return ase;

    }


    private ESDescriptor mergeDescriptors(BaseDescriptor des1, BaseDescriptor des2) {
        if (des1 instanceof ESDescriptor && des2 instanceof ESDescriptor) {
            ESDescriptor esds1 = (ESDescriptor) des1;
            ESDescriptor esds2 = (ESDescriptor) des2;
            if (esds1.getURLFlag() != esds2.getURLFlag()) {
                return null;
            }
            if (esds1.getURLLength() != esds2.getURLLength()) {
                // ignore different urls
            }
            if (esds1.getDependsOnEsId() != esds2.getDependsOnEsId()) {
                return null;
            }
            if (esds1.getEsId() != esds2.getEsId()) {
                return null;
            }
            if (esds1.getoCREsId() != esds2.getoCREsId()) {
                return null;
            }
            if (esds1.getoCRstreamFlag() != esds2.getoCRstreamFlag()) {
                return null;
            }
            if (esds1.getRemoteODFlag() != esds2.getRemoteODFlag()) {
                return null;
            }
            if (esds1.getStreamDependenceFlag() != esds2.getStreamDependenceFlag()) {
                return null;
            }
            if (esds1.getStreamPriority() != esds2.getStreamPriority()) {
                // use stream prio of first (why not)
            }
            if (esds1.getURLString() != null ? !esds1.getURLString().equals(esds2.getURLString()) : esds2.getURLString() != null) {
                // ignore different urls
            }
            if (esds1.getDecoderConfigDescriptor() != null ? !esds1.getDecoderConfigDescriptor().equals(esds2.getDecoderConfigDescriptor()) : esds2.getDecoderConfigDescriptor() != null) {
                DecoderConfigDescriptor dcd1 = esds1.getDecoderConfigDescriptor();
                DecoderConfigDescriptor dcd2 = esds2.getDecoderConfigDescriptor();

                if (dcd1.getAudioSpecificInfo() != null && dcd2.getAudioSpecificInfo() != null && !dcd1.getAudioSpecificInfo().equals(dcd2.getAudioSpecificInfo())) {
                    return null;
                }
                if (dcd1.getAvgBitRate() != dcd2.getAvgBitRate()) {
                    dcd1.setAvgBitRate((dcd1.getAvgBitRate() + dcd2.getAvgBitRate()) / 2);
                }
                if (dcd1.getBufferSizeDB() != dcd2.getBufferSizeDB()) {
                    // I don't care
                }

                if (dcd1.getDecoderSpecificInfo() != null ? !dcd1.getDecoderSpecificInfo().equals(dcd2.getDecoderSpecificInfo()) : dcd2.getDecoderSpecificInfo() != null) {
                    return null;
                }

                if (dcd1.getMaxBitRate() != dcd2.getMaxBitRate()) {
                    dcd1.setMaxBitRate(Math.max(dcd1.getMaxBitRate(), dcd2.getMaxBitRate()));
                }
                if (!dcd1.getProfileLevelIndicationDescriptors().equals(dcd2.getProfileLevelIndicationDescriptors())) {
                    return null;
                }

                if (dcd1.getObjectTypeIndication() != dcd2.getObjectTypeIndication()) {
                    return null;
                }
                if (dcd1.getStreamType() != dcd2.getStreamType()) {
                    return null;
                }
                if (dcd1.getUpStream() != dcd2.getUpStream()) {
                    return null;
                }

            }
            if (esds1.getOtherDescriptors() != null ? !esds1.getOtherDescriptors().equals(esds2.getOtherDescriptors()) : esds2.getOtherDescriptors() != null) {
                return null;
            }
            if (esds1.getSlConfigDescriptor() != null ? !esds1.getSlConfigDescriptor().equals(esds2.getSlConfigDescriptor()) : esds2.getSlConfigDescriptor() != null) {
                return null;
            }
            return esds1;
        } else {
            LOG.logError("I can only merge ESDescriptors");
            return null;
        }
    }

    public List<Sample> getSamples() {


        return lists;
    }

    public SampleDescriptionBox getSampleDescriptionBox() {
        return stsd;
    }

    public synchronized long[] getSampleDurations() {
        return decodingTimes;
    }

    public List<CompositionTimeToSample.Entry> getCompositionTimeEntries() {
        if (tracks[0].getCompositionTimeEntries() != null && !tracks[0].getCompositionTimeEntries().isEmpty()) {
            List<int[]> lists = new LinkedList<int[]>();
            for (Track track : tracks) {
                lists.add(CompositionTimeToSample.blowupCompositionTimes(track.getCompositionTimeEntries()));
            }
            LinkedList<CompositionTimeToSample.Entry> compositionTimeEntries = new LinkedList<CompositionTimeToSample.Entry>();
            for (int[] list : lists) {
                for (int compositionTime : list) {
                    if (compositionTimeEntries.isEmpty() || compositionTimeEntries.getLast().getOffset() != compositionTime) {
                        CompositionTimeToSample.Entry e = new CompositionTimeToSample.Entry(1, compositionTime);
                        compositionTimeEntries.add(e);
                    } else {
                        CompositionTimeToSample.Entry e = compositionTimeEntries.getLast();
                        e.setCount(e.getCount() + 1);
                    }
                }
            }
            return compositionTimeEntries;
        } else {
            return null;
        }
    }

    public long[] getSyncSamples() {
        if (tracks[0].getSyncSamples() != null && tracks[0].getSyncSamples().length > 0) {
            int numSyncSamples = 0;
            for (Track track : tracks) {
                numSyncSamples += track.getSyncSamples() != null ? track.getSyncSamples().length : 0;
            }
            long[] returnSyncSamples = new long[numSyncSamples];

            int pos = 0;
            long samplesBefore = 0;
            for (Track track : tracks) {
                if (track.getSyncSamples() != null) {
                    for (long l : track.getSyncSamples()) {
                        returnSyncSamples[pos++] = samplesBefore + l;
                    }
                }
                samplesBefore += track.getSamples().size();
            }
            return returnSyncSamples;
        } else {
            return null;
        }
    }

    public List<SampleDependencyTypeBox.Entry> getSampleDependencies() {
        if (tracks[0].getSampleDependencies() != null && !tracks[0].getSampleDependencies().isEmpty()) {
            List<SampleDependencyTypeBox.Entry> list = new LinkedList<SampleDependencyTypeBox.Entry>();
            for (Track track : tracks) {
                list.addAll(track.getSampleDependencies());
            }
            return list;
        } else {
            return null;
        }
    }

    public TrackMetaData getTrackMetaData() {
        return tracks[0].getTrackMetaData();
    }

    public String getHandler() {
        return tracks[0].getHandler();
    }

    public SubSampleInformationBox getSubsampleInformationBox() {
        return tracks[0].getSubsampleInformationBox();
    }

}
