public static void main(String[] args) {

    if (args.length != 12 && args.lengt != 8) {
        System.out.println("Error: Command line args must be of length 12 or 8 (Not including executable)");
        System.exit(1);
    }

    int sourcePort;
    String fileName;
    int mtu;
    int sws;
    int destIP = -1;
    int destPort = -1;

    if (!args[0].equals("-p")) {
        System.out.println("Error: First arg must contain -p flag");
        System.exit(1);
    }

    try {
        sourcePort = Integer.parseInt(args[1]);
        if (sourcePort < 0 || sourcePort > 65_535) {
            System.out.println("Error: Source port must be in range 0-65535");
            System.exit(1);
        }
    } 
    catch (NumberFormatException e) {
        System.out.println("Error: Source port not a number");
        System.exit(1);
    }

    String mode = args[2];

    if (!mode.equals("-s") && !mode.equals("-m")) {
        System.out.println("Error: Mode must be -s or -m");
        System.exit(1);
    }

    if (mode.equals("-s")) {
        try {
            destIP = Integer.parseInt(args[3]);
            if (destIP < 0) {
                System.out.println("Error: Destination IP must be positive");
                System.exit(1);
            }
        }
        catch (NumberFormatException e) {
            System.out.println("Error: Destination IP not a number");
            System.exit(1);
        }
    }
    else {
        try {
            mtu = Integer.parseInt(args[3]);
            if (mtu < 0) {
                System.out.println("Error: MTU must be positive");
                System.exit(1);
            }

        }
        catch (NumberFormatException e) {
            System.out.println("Error: MTU not a number");
            System.exit(1);
        }
    }
    String nextOption = args[4];
    if (!nextOption.equals("-a") && !nextOption.equals("-c")) {
        System.out.println("Error: Option must be -a or -c");
        System.exit(1);
    }

    if (nextOption.equals("-a")) {
        try {
            destPort = Integer.parseInt(args[5]);
            if (destPort < 0 || destPort > 65_535) {
                System.out.println("Error: Destination port must be in range 0-65535");
                System.exit(1);
            }
        }
        catch (NumberFormatException e) {
            System.out.println("Error: Destination port not a number");
            System.exit(1);
        }
    }

    if (nextOption.equals("-c") {
        try {
            sws = Integer.parseInt(args[5]);
            if (sws < 0) {
                System.out.println("Error: SWS must be positive");
                System.exit(1);
            }
        }
        catch (NumberFormatException e) {
            System.out.println("Error: SWS not a number");
            System.exit(1);
        }
    }

    if (!args[6].equals("-f")) {
        System.out.println("Error: must specify filename with -f");
        System.exit(1);
    }

    fileName = args[7];

    if (args.length() == 12) {
        if (!args[8].equals("-m")) {
            System.out.println("Error: must specify MTU with -m");
            System.exit(1);
        }
        try {
            mtu = Integer.parseInt(args[9]);
            if (mtu < 0) {
                System.out.println("Error: MTU must be positive");
                System.exit(1);
            }

        }
        catch (NumberFormatException e) {
            System.out.println("Error: MTU not a number");
            System.exit(1);
        }
        if (!args[10].equals("-c")) {
            System.out.println("Error: must specify SWS with -c");
            System.exit(1);
        }
        try {
            sws = Integer.parseInt(args[11]);
            if (sws < 0) {
                System.out.println("Error: SWS must be positive");
                System.exit(1);
            }

        }
        catch (NumberFormatException e) {
            System.out.println("Error: SWS not a number");
            System.exit(1);
        }

        if (mode.equals("-s")) {
            startSender();
        }

    }

    else if (mode.equals("-m")) {
        startReciever();
    }
}
