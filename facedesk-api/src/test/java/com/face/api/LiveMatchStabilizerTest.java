package com.face.api;
import com.face.api.service.LiveMatchStabilizer;
import com.face.api.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LiveMatchStabilizerTest {
    VideoFaceResult face(String code,int x){return new VideoFaceResult(new FaceBox(x,0,x+100,100),code==null?"UNKNOWN":"MATCHED",code==null?null:new RecognitionResult("Guest",code,code,.65,null));}
    VideoFaceResult update(LiveMatchStabilizer s,String code,int x,long time){VideoFaceResult r=face(code,x);s.update(Collections.singletonList(r),time);return r;}
    @Test void confirmsRepeatedEvidenceAndNeverCarriesAnIdentityToUnknownFrames(){
        LiveMatchStabilizer s=new LiveMatchStabilizer();assertFalse(update(s,"A",0,1000).confirmed);assertFalse(update(s,"A",2,1100).confirmed);assertTrue(update(s,"A",4,1200).confirmed);
        VideoFaceResult missing=update(s,null,5,1300);assertEquals("RECHECK",missing.displayState);assertNull(missing.match);assertFalse(missing.confirmed);
        assertTrue(update(s,"A",6,1400).confirmed);assertFalse(update(s,"B",7,1500).confirmed);
        assertEquals("UNMATCHED",update(s,null,7,2300).displayState);
    }
    @Test void requiresNewEvidenceAfterDisappearanceOrPositionDiscontinuity(){
        LiveMatchStabilizer s=new LiveMatchStabilizer();for(int i=0;i<3;i++)update(s,"A",0,1000+i*100);
        assertFalse(update(s,"A",400,1300).confirmed);assertFalse(update(s,"A",0,2400).confirmed);
    }
}
