package edu.georgetown.library.fileAnalyzer.filetest;

import gov.nara.nwts.ftapp.FTDriver;
import gov.nara.nwts.ftapp.filetest.ActionRegistry;

/** 
 * Initialize the File Analzyer with generic image processing rules (but not NARA specific business rules)
 * @author TBrady
 *
 */
public class DSpaceActionRegistry extends ActionRegistry {
	
	private static final long serialVersionUID = 1L;

	public DSpaceActionRegistry(FTDriver dt, boolean modifyAllowed) {
		super(dt, modifyAllowed);
		add(new IngestInventory(dt));
		add(new IngestValidate(dt));
		add(new DSpaceDAT(dt));
		add(new ProquestToIngest(dt));
		add(new ProquestQC(dt));
	}
	
}
