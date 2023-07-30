import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * The Glicko System
 * <br />
 * A standalone version taken from Jason Brownlee's codebase for
 * the Kaggle Chess rating competition. See http://kaggle.com/chess
 * <br />
 * Gets about 0.776826 on the submission system with the current configuration, which is only minimally optimized.
 * <br />
 * I had to patch/hack in parts of the infrastructure to get this to work standalone - it's really ugly! Sorry!
 * The complete codebase will be released at the end of the competition
 * at http://github.com/jbrownlee/ChessML
 * <br />
 * Update RD's on a game-by-game basis - as performed by FICS. Rating period is determined in months
 * <br />
 * See:
 * <ul>
 * 	<li>http://math.bu.edu/people/mg/glicko/glicko.doc/glicko.html</li>
 *  <li>http://en.wikipedia.org/wiki/Glicko_rating_system</li>
 *  <li>http://www.glicko.net/glicko/glicko2.doc/example.html</li>
 * </ul>
 * Compile with java 1.5 or 1.6: <pre>java -cp . GlickoSystemStandalone.java</pre>
 * <br />
 * Usage: <pre>java GlickoSystemStandalone training_data.csv test_data.csv</pre>
 * <br />
 * It will generate a submission.csv file.
 * <br />
 * (C) Copyright 2010 Jason Brownlee. Some Rights Reserved.
 * This work is licensed under a Creative Commons Attribution-Noncommercial-Share Alike 2.5 Australia License.
 * http://creativecommons.org/licenses/by-nc-sa/2.5/au/
 */
public class GlickoSystemStandalone
{
	public final static boolean PRINT_DEBUG = true;

	// data
	public Map<Integer, UserRating> ratings = new HashMap<Integer, UserRating>();

	// parameters (constraints/seeding)
	private static double defaultRating = 1500;
	private static double defaultRD = 350;
	private static double minRD = 30;

	// parameters used in C calculation
	private static double defaultRDDecayTimePeriod = 30; // 30
	private static double avgRD = 200; // 50?

	private boolean updateRatingsDuringtest = false; // false
	private boolean updateAfterEveryGame = false; // false

	@Override
	public String toString() {
		return super.toString() +
			" [" +
			"defaultRating="+defaultRating+", " +
			"defaultRD="+defaultRD+", " +
			"defaultRDDecayTimePeriod="+defaultRDDecayTimePeriod+", " +
			"minRD="+minRD+", " +
			"avgRD="+avgRD+", " +
			"updateRatingsDuringtest="+updateRatingsDuringtest+", " +
			"updateAfterEveryGame="+updateAfterEveryGame+
			"]";
	}


	private GlickoSystemStandalone()
	{}

	public static GlickoSystemStandalone getInstanceBatchAndUpdateDuringTest()
	{
		GlickoSystemStandalone e = new GlickoSystemStandalone();
		e.updateAfterEveryGame = true; // batch
		e.updateRatingsDuringtest = true; // update during test
		return e;
	}
	public static GlickoSystemStandalone getInstanceBatchAndNoUpdateDuringTest()
	{
		GlickoSystemStandalone e = new GlickoSystemStandalone();
		e.updateAfterEveryGame = true; // batch
		e.updateRatingsDuringtest = false; // update during test
		return e;
	}
	public static GlickoSystemStandalone getInstanceNoBatchAndUpdateDuringTest()
	{
		GlickoSystemStandalone e = new GlickoSystemStandalone();
		e.updateAfterEveryGame = false; // batch
		e.updateRatingsDuringtest = true; // update during test
		return e;
	}
	public static GlickoSystemStandalone getInstanceNoBatchAndNoUpdateDuringTest()
	{
		GlickoSystemStandalone e = new GlickoSystemStandalone();
		e.updateAfterEveryGame = false; // batch
		e.updateRatingsDuringtest = false; // update during test
		return e;
	}

	/**
	 * calculate the current RD from the old RD
	 *
	 * @param RDold - old RD value
	 * @param c - a constant that governs the increase in uncertainty over time
	 * @param t - the number of rating periods since last competition
	 * 	(e.g., if the player competed in the most recent rating period, )
	 * @return
	 */
	public final static double calculateCurrentRD(
			double RDold,
			double c,
			double t)
	{
		double value = Math.sqrt((RDold*RDold) + ((c*c)*t));
		return Math.min(value, defaultRD);
	}

	/**
	 * Calculate the decayed RD for an average RD, c, and a default decay time period
	 * @param c
	 * @return
	 */
	public final static double testCValue(double c)
	{
		return Math.sqrt((avgRD*avgRD) + (c*c)*defaultRDDecayTimePeriod);
	}

	/**
	 * calculate C for a given default RD, average RD, and default RD decay time period
	 * @return
	 */
	public final static double calculateC()
	{
		// sqrt [(350^2 - 50^2)/30]
		return Math.sqrt(((defaultRD*defaultRD)-(avgRD*avgRD))/defaultRDDecayTimePeriod);
	}

	/**
	 * Calculate a new rating at the end of a period
	 *
	 * @param rating
	 * @param rd
	 * @param opponentRatings
	 * @param opponentRDs
	 * @param outcomes
	 * @return
	 */
	public final static double calculateNewRating(
			double rating,
			double rd,
			double [] opponentRatings,
			double [] opponentRDs,
			double [] outcomes)
	{
		double q = calculateQ();
		double dSquared = calculateDSquared(rating, opponentRatings, opponentRDs);
		double division = (q/((1.0/(rd*rd)) + (1.0/dSquared)));

		double sum = 0.0;
		int m = opponentRatings.length;
		for (int i = 0; i < m; i++) {
			double grd = calculateG(opponentRDs[i]);
			double estimate = estimateOutcome(rating, opponentRatings[i], opponentRDs[i]);
			sum += (grd * (outcomes[i]-estimate));
                }
		return rating + division * sum;
	}

	/**
	 * Calculate a RD at the end of a period
	 *
	 * @param rating
	 * @param rd
	 * @param opponentRatings
	 * @param opponentRDs
	 * @return
	 */
	public final static double calculateNewRD(
			double rating,
			double rd,
			double [] opponentRatings,
			double [] opponentRDs)
	{
		double dSquared = calculateDSquared(rating, opponentRatings, opponentRDs);
		double part = (1.0/(rd*rd)) + (1.0/dSquared);
		double newRD = Math.sqrt(Math.pow(part, -1.0));

		// allow ratings to change over short time periods
		if (newRD < minRD) {
			newRD = minRD;
		}

		return newRD;
	}

	/**
	 * Calculate Q to double precision
	 *
	 * @return
	 */
	public final static double calculateQ()
	{
		return (Math.log(10.0)/400.0); // 0.0057565
	}

	/**
	 * Calculate g(RD)
	 * @param rd
	 * @return
	 */
	public final static double calculateG(double rd)
	{
		double q = calculateQ();
		return 1.0 / Math.sqrt(1.0+3.0*(q*q) * ((rd*rd)/(Math.PI*Math.PI)) );
	}

	/**
	 * Estimate the outcome for a player game
	 *
	 * @param rating
	 * @param opponentRating
	 * @param opponentRD
	 * @return
	 */
	public final static double estimateOutcome(
			double rating,
			double opponentRating,
			double opponentRD)
	{
		double exponent = -calculateG(opponentRD) * (rating-opponentRating) / 400.0;
		return 1.0 / (1.0 + Math.pow(10.0, exponent));
	}

	/**
	 *
	 * @param rating
	 * @param opponentRatings
	 * @param opponentRDs
	 * @return
	 */
	public final static double calculateDSquared(
			double rating,
			double [] opponentRatings,
			double [] opponentRDs)
	{
		double sum = 0.0;
		for (int i = 0; i < opponentRatings.length; i++) {
			double grd = calculateG(opponentRDs[i]);
			double estimate = estimateOutcome(rating, opponentRatings[i], opponentRDs[i]);
			sum += ((grd*grd) * estimate * (1.0-estimate));
                }
		double q = calculateQ();
		return Math.pow((q*q)*sum, -1.0);
	}

	/**
	 * Testing the maths - seems good
	 *
	 * @param args
	 */
	public static void mainOld(String[] args)
	{
	    // http://math.bu.edu/people/mg/glicko/glicko.doc/glicko.html

	    /* To demonstrate Step 2 of the calculations above, suppose a player
	     * rated 1500 competes against players rated 1400, 1550 and 1700,
	     * winning the first game and losing the next two. Assume the 1500-rated
	     * player's rating deviation is 200, and his opponents' are 30, 100
	     * and 300, respectively.
	     * */
	    double rating = 1500;
	    double rd = 200;

	    // correct
	    System.out.println("Q: expect=0.0057565, got=" + calculateQ());
	    // correct
	    System.out.println("g(RD): expect=0.9955, got="+calculateG(30));
	    System.out.println("g(RD): expect=0.9531, got="+calculateG(100));
	    System.out.println("g(RD): expect=0.7242, got="+calculateG(300));

	    System.out.println("Estimate: expect=0.639, got="+estimateOutcome(rating, 1400, 30));
	    System.out.println("Estimate: expect=0.432, got="+estimateOutcome(rating, 1550, 100));
	    System.out.println("Estimate: expect=0.303, got="+estimateOutcome(rating, 1700, 300));

	    System.out.println("D^2: expect=231.67^2 =53670.85, got="+calculateDSquared(rating, new double[]{1400,1550,1700}, new double[]{30, 100, 300}));

	    System.out.println("Rating: expect=1464, got: " + calculateNewRating(
			    rating, rd, new double[]{1400,1550,1700}, new double[]{30, 100, 300}, new double[]{1,0,0}));
	    System.out.println("RD: expect=151.4, got: " + calculateNewRD(rating, rd, new double[]{1400,1550,1700}, new double[]{30, 100, 300}));

	    System.out.println("C (63.2): expected=350, got="+testCValue(63.2));
	    System.out.println(calculateC());
    }

	/**
	 * Usage: java GlickoSystemStandalone training_data.csv test_data.csv
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception
	{
		// most basic validation
		if (args.length != 2) {
			System.out.println("Usage: java GlickoSystemStandalone training_data.csv test_data.csv");
			System.exit(1);
		}
		// train
		List<double[]> training = loadDataset(new File(args[0]));
		GlickoSystemStandalone g = new GlickoSystemStandalone();
		System.out.println("Model: " + g);
		g.trainModel(training);

		// generate submission file
		List<double[]> test = loadDataset(new File(args[1]));
		double [] predictions = g.batchPredictions(test);
		// save
		saveDataset(test, predictions, new File("submission.csv"));
		System.out.println("Wrote file: submission.csv, done.");
	}


	public double [] batchPredictions(List<double[]> recordSet)
	{
		// prepare predictions
		double [] predictions = new double[recordSet.size()];

		if (updateRatingsDuringtest)
		{
			// process months
			Map<Short,List<double[]>> monthMap = getGamesByMonth(recordSet);
			List<Short> monthList = getOrderedMonthList(monthMap);
			for(Short month : monthList)
			{
				// get all records for period
				List<double[]> records = monthMap.get(month);

				if (updateAfterEveryGame) {
					perGameUpdatesForUserMonths(month, records, true);
				} else {
					batchUpdatesForUserMonths(month, records, true);
				}

				printStats();
			}
		} else {
			// calculate

			for (int i = 0; i < predictions.length; i++) {
				double [] record = recordSet.get(i);
				predictions[i] = predictResult(record);
	        }
		}

		return predictions;
	}


	public double predictResult(double [] record)
	{
		// basic idea...
		UserRating white = ratings.get(new Integer((int)record[1]));
		UserRating black = ratings.get(new Integer((int)record[2]));
		// estimate
		return estimateOutcome(white.rating, black.rating, black.rd);
	}

	public void trainModel(List<double[]> trainingSet)
	{
		// prepare all users
		Map<Integer,List<double[]>> userMap = getGamesByUser(trainingSet);
		List<Integer> userList = getOrderedUserList(userMap);
		for(Integer userId : userList)
		{
			ratings.put(userId, new UserRating(userId));
		}

		// process months
		Map<Short,List<double[]>> monthMap = getGamesByMonth(trainingSet);
		List<Short> monthList = getOrderedMonthList(monthMap);
		for(Short month : monthList)
		{
			// get all records for period
			List<double[]> records = monthMap.get(month);

			if (updateAfterEveryGame) {
				perGameUpdatesForUserMonths(month, records, false);
			} else {
				batchUpdatesForUserMonths(month, records, false);
			}

			printStats();
		}
	}

	public void perGameUpdatesForUserMonths(Short month, List<double[]> records, boolean isTest)
	{
		// process all games in the month in turn
		for(double[] record : records)
		{
			UserRating white = ratings.get(new Integer((int)record[1]));
			UserRating black = ratings.get(new Integer((int)record[2]));

			// use outcome for user in record or estimate
			double whiteOutcome = (isTest) ? estimateOutcome(white.rating, black.rating, black.rd) : getOutcomeForPlayer(record, white.userId);
			double blackOutcome = (isTest) ? estimateOutcome(black.rating, white.rating, white.rd) : getOutcomeForPlayer(record, black.userId);

			// update white
			updateRatingsForUser(white.userId,
					month,
					new double[]{black.rating},
					new double[]{black.rd},
					new double[]{whiteOutcome});
			// update black
			updateRatingsForUser(black.userId,
					month,
					new double[]{white.rating},
					new double[]{white.rd},
					new double[]{blackOutcome});
			// apply
			applyRatingsAndRds(white.userId, month);
			applyRatingsAndRds(black.userId, month);
		}

	}

	public static double getOutcomeForPlayer(double [] record, double userId)
	{
		// white
		if(record[1]==userId) {
			return record[3];
		}

		// black
		return 1.0-record[3];
	}

	/**
	 * Batch updates - process all games for a user month then update ratings
	 *
	 * @param month
	 * @param records
	 */
	public void batchUpdatesForUserMonths(Short month, List<double[]> records, boolean isTest)
	{
		// process all records for the period by user
		Map<Integer,List<double[]>> userMap = getGamesByUser(records);
		// process each user for the period
		for(Integer userId : userMap.keySet())
		{
			UserRating user = ratings.get(userId);
			List<double[]> userPeriodRecords = userMap.get(userId);
			double [] outcomes = new double[userPeriodRecords.size()];
			for (int i = 0; i < outcomes.length; i++)
			{
				if (isTest)
				{
					// estimate the outcome for the user and use that
					double[] record = userPeriodRecords.get(i);
					Integer opponentId = (record[1]==(double)userId.intValue()) ? new Integer((int)record[2]) : new Integer((int)record[1]);
					UserRating opponent = ratings.get(opponentId);
					// calculate estimated outcome for player and use as outcome
					outcomes[i] = estimateOutcome(user.rating, opponent.rating, opponent.rd);
				}
				else
				{
					// use outcome in record for the player
					double[] record = userPeriodRecords.get(i);
					outcomes[i] = getOutcomeForPlayer(record, userId);
				}
			}
			// update
			updateRatingsForUserPeriod(userId, month, userPeriodRecords, outcomes);
		}

		// map old ratings and rd to new ratings and rd
		for(Integer userId : userMap.keySet())
		{
			applyRatingsAndRds(userId, month);
		}
	}

	public void applyRatingsAndRds(Integer userId, short month)
	{
		UserRating user = ratings.get(userId);
		// transfer rating and rd
		user.rating = user.tmpRating;
		user.rd = user.tmpRd;
		// update last played month
		user.monthLastPlayed = month;
	}

	public void updateRatingsForUserPeriod(
			Integer userId,
			Short month,
			List<double[]> records,
			double [] outcomes)
	{
		// collect information for the user-period
		double [] opponentRatings = new double[records.size()];
		double [] opponentRDs = new double[records.size()];

		for (int i = 0; i < records.size(); i++)
		{
			// get record
			double[] record = records.get(i);
			// get opponent
			Integer opponentId = (record[1]==(double)userId.intValue()) ? new Integer((int)record[2]) : new Integer((int)record[1]);
			UserRating opponent = ratings.get(opponentId);
			// capture ratings and rds
			opponentRatings[i] = opponent.rating;
			opponentRDs[i] = opponent.rd;
		}

		// update
		updateRatingsForUser(userId, month, opponentRatings, opponentRDs, outcomes);
	}

	public void updateRatingsForUser(
			Integer userId,
			Short month,
			double [] opponentRatings,
			double [] opponentRDs,
			double [] outcomes)
	{
		// prepare data
		UserRating user = ratings.get(userId);
		double rating = user.rating;
		double c = calculateC();
		double t = user.getNumTimePeriodsSinceLastGame(month);
		double rd = calculateCurrentRD(user.rd, c, t);

		// calculate new rating
		user.tmpRating = calculateNewRating(
				rating,
				rd,
				opponentRatings,
				opponentRDs,
				outcomes);
		// calculate new rd
		user.tmpRd = calculateNewRD(rating, rd, opponentRatings, opponentRDs);
	}

	protected void printStats()
	{
		if (!PRINT_DEBUG) {
			return;
		}

		List<Double> r = new LinkedList<Double>();
		List<Double> rds = new LinkedList<Double>();

		for(Integer userId : ratings.keySet())
		{
			UserRating user = ratings.get(userId);
			r.add(new Double(user.rating));
			rds.add(new Double(user.rd));
		}

		double [] ratingsStats = toSummary(r);
		double [] rdsStats = toSummary(rds);

		System.out.println("Ratings: min=" +ratingsStats[0]+
				", avg=" +ratingsStats[2]+
				", max=" +ratingsStats[1]+
				", RDs: min=" +rdsStats[0]+
				", avg=" + rdsStats[2]+
				", max=" + rdsStats[1]);

	}

	public static class UserRating
	{
		public final int userId;

		public double rating;
		public double rd;

		public double tmpRating;
		public double tmpRd;

		public double monthLastPlayed = Double.NaN;

		public UserRating(int aUserId){
			userId = aUserId;
			rating = defaultRating;
			rd = defaultRD;
		}

		public double getNumTimePeriodsSinceLastGame(double month)
		{
			if (Double.isNaN(monthLastPlayed))
			{
				monthLastPlayed = month;
			}

			// (e.g., if the player competed in the most recent rating period, t=1)
			return 1+(month-monthLastPlayed);
		}
	}

	public static class GameRecord
	{
		public double[] record;

		public double whiteRating;
		public double whiteRd;

		public double blackRating;
		public double blackRd;

		public double outcome;
		public double expectationWhite;
		public double expectationBlack;
	}

	//
	// hacked in infrastructure (pox!)
	//

	public static List<double[]> loadDataset(File file) throws IOException
    {
    	String raw = fastLoadFileAsString(file);

    	String [] lines = raw.split("\n");
    	List<double []> list = new LinkedList<double[]>();
    	// skip first line
    	for (int i = 1; i < lines.length; i++) {
    		String [] parts = lines[i].split(",");
    		// no error checking - who cares
    		double [] d = new double[4];
    		Arrays.fill(d, Double.NaN);
    		d[0] = Double.parseDouble(parts[0]);
    		d[1] = Double.parseDouble(parts[1]);
    		d[2] = Double.parseDouble(parts[2]);
    		if (parts.length==4) {
    			d[3] = Double.parseDouble(parts[3]);
    		}
    		list.add(d);
        }
    	return list;
    }
	public static String fastLoadFileAsString(File file)
		throws IOException
	{
		StringBuffer buf = new StringBuffer();
		char [] dataBuffer = new char[1024 * 10]; // 10KB

		FileReader reader = new FileReader(file);

		try {
			int count = 0;
			while((count=reader.read(dataBuffer)) > 0) {
				buf.append(dataBuffer, 0, count);
			}
		} finally {
			reader.close();
		}


		return buf.toString();
	}
	public static double [] toSummary(List<Double> values){
		double min = Double.MAX_VALUE;
		double max = Double.MIN_VALUE;

		double sum = 0;
		int num = 0;

		for(Double d : values) {
			if (d.isNaN()) {
				continue;
			}

			sum += d.doubleValue();
			num++;
			if (d.doubleValue() < min) min = d.doubleValue();
			if (d.doubleValue() > max) max = d.doubleValue();
		}

		double avg = (sum/(double)num);
		return new double []{min,max,avg};
	}
	/**
	 * Save a dataset to disk as csv, can be used for submission files
	 * for example. Written to memory first then to disk - fast!
	 * @param dataset
	 * @param file
	 * @throws IOException
	 */
	public final static String FIRST_LINE = "\"Month #\",\"White Player #\",\"Black Player #\",\"Score\"";
	public static void saveDataset(List<double[]> dataset, double [] predictions, File file)
		throws IOException
	{
		// do a big malloc
		StringBuffer buf = new StringBuffer(dataset.size()*20);
		// header
		buf.append(FIRST_LINE);
		buf.append("\n");
		// lines
		for (int i = 0; i < predictions.length; i++) {
			double[] record = dataset.get(i);
			buf.append(record[0]); // m
			buf.append(",");
			buf.append(record[1]); // p1
			buf.append(",");
			buf.append(record[2]); // p2
			buf.append(",");
			buf.append(predictions[i]); // outcome
			buf.append("\n");
		}
		// write to disk
		writeStringToFile(buf.toString(), file);
	}

	/**
	 * Write a string to disk
	 *
	 * @param data
	 * @param file
	 * @throws IOException
	 */
	public static void writeStringToFile(String data, File file)
			throws IOException
	{

			// write to disk
			FileWriter writer = new FileWriter(file);
			try {
				writer.write(data);
			} finally {
				writer.close();
			}
	}
	public static Map<Integer,List<double[]>> getGamesByUser(List<double[]> records)
	{
		Map<Integer,List<double[]>> userMap = new HashMap<Integer, List<double[]>>();

		// process all records
		for(double[] record : records) {
			// white
			addUser(new Integer((int)record[1]), record, userMap);
			// black
			addUser(new Integer((int)record[2]), record, userMap);
		}
		return userMap;
	}
	protected static void addUser(Integer userId, double[] record, Map<Integer, List<double[]>> userMap) {
		List<double[]> list = userMap.get(userId);
		if (list == null) {
			list = new LinkedList<double[]>();
			userMap.put(userId, list);
		}
		list.add(record);
	}
	public static Map<Short,List<double[]>> getGamesByMonth(List<double[]> records)
	{
		Map<Short,List<double[]>> monthMap = new HashMap<Short, List<double[]>>();

		// process all records
		for(double[] record : records) {
			addMonth(new Short((short)record[0]), record, monthMap);
		}
		return monthMap;
	}
	protected static void addMonth(Short monthId, double[] record, Map<Short, List<double[]>> monthMap) {
		List<double[]> list = monthMap.get(monthId);
		if (list == null) {
			list = new LinkedList<double[]>();
			monthMap.put(monthId, list);
		}
		list.add(record);
	}

	public List<Short> getOrderedMonthList(Map<Short, List<double[]>> monthMap) {
		List<Short> list = new LinkedList<Short>();
		list.addAll(monthMap.keySet());
		Collections.sort(list);
		return list;
	}

	public List<Integer> getOrderedUserList(Map<Integer, List<double[]>> userMap) {
		List<Integer> list = new LinkedList<Integer>();
		list.addAll(userMap.keySet());
		Collections.sort(list);
		return list;
	}
}
