package android.graphics;

// Droidsafe Imports
import droidsafe.runtime.*;
import droidsafe.helpers.*;
import droidsafe.annotations.*;

public class MaskFilter {
    
    @DSComment("Private Method")
    @DSBan(DSCat.PRIVATE_METHOD)
    private static void nativeDestructor(int native_filter) {
    }
@DSGeneratedField(tool_name = "Doppelganger", tool_version = "2.0", generated_on = "2013-12-30 12:34:24.387 -0500", hash_original_field = "1353DF0D3FEF59358BA81F3F4AC59875", hash_generated_field = "1353DF0D3FEF59358BA81F3F4AC59875")

    int native_instance;
    
    @DSComment("From safe class list")
    @DSSafe(DSCat.SAFE_OTHERS)
    @DSGenerator(tool_name = "Doppelganger", tool_version = "0.4.2", generated_on = "2013-07-17 10:23:10.260 -0400", hash_original_method = "C1D6709FDC433FB6BE788CD4DDEC7BFF", hash_generated_method = "C1D6709FDC433FB6BE788CD4DDEC7BFF")
    public MaskFilter ()
    {
        //Synthesized constructor
    }

    @DSComment("From safe class list")
    @DSSafe(DSCat.SAFE_LIST)
    @DSGenerator(tool_name = "Doppelganger", tool_version = "2.0", generated_on = "2013-12-30 12:34:24.382 -0500", hash_original_method = "CA9745465B207BED8177C430188A7C4B", hash_generated_method = "1B58F815E13231E7E163ED79B267DEA7")
    
protected void finalize() throws Throwable {
        nativeDestructor(native_instance);
    }
    
}
