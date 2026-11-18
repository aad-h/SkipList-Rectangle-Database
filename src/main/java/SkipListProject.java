import java.io.File;
import java.io.FileNotFoundException;
import java.util.Scanner;

// On my honor:
// - I have not used source code obtained from another student,
// or any other unauthorized source, either modified or unmodified.
// - All source code and documentation used in my program is
// either my original work, or was derived by me from the
// source code published in the textbook for this course.
// - I have not discussed coding details about this project with
// anyone other than the instructor, ACM/UPE tutors or the TAs assigned
// to this course. I understand that I may discuss the concepts
// of this program with other students, and that another student
// may help me debug my program so long as neither of us writes
// anything during the discussion or modifies any computer file
// during the discussion. I have violated neither the spirit nor
// letter of this restriction.
// sreyas aadihira

/**
 * The class containing the main method, the entry point of the application. It
 * will take a command line file argument which include the commands to be read
 * and creates the appropriate SkipList object and outputs the correct results
 * to the console as specified in the file.
 *
 * @author CS Staff
 * 
 * @version 2024-01-22
 */
public class SkipListProject {

    /**
     * The entry point of the application.
     *
     * @param args
     *            The name of the command file passed in as a command line
     *            argument.
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println(
                "Invalid file. No filename in command line arguments");
            return;
        }

        // reads the file line by line and feeds each command to the processor
        CommandProcessor cmdProc = new CommandProcessor();
        try (Scanner scanner = new Scanner(new File(args[0]))) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (!line.isEmpty()) {
                    cmdProc.processor(line);
                }
            }
        }
        catch (FileNotFoundException e) {
            System.out.println("Invalid file: " + args[0]);
        }
    }
}
